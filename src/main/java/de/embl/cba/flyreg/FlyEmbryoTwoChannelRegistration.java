package de.embl.cba.flyreg;

import de.embl.cba.abberation.RefractiveIndexMismatchCorrectionSettings;
import de.embl.cba.abberation.RefractiveIndexMismatchCorrections;
import de.embl.cba.morphometry.*;
import de.embl.cba.morphometry.geometry.CentroidsParameters;
import de.embl.cba.morphometry.geometry.CoordinatesAndValues;
import de.embl.cba.morphometry.geometry.CurveAnalysis;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidMLJ;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidsMLJ;
import de.embl.cba.morphometry.regions.Regions;
import de.embl.cba.transforms.utils.Transforms;
import net.imagej.ops.OpService;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.labeling.ConnectedComponents;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.converter.Converters;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegion;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.roi.labeling.LabelingType;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import java.util.ArrayList;
import java.util.List;

import static de.embl.cba.morphometry.Constants.*;
import static de.embl.cba.morphometry.viewing.BdvViewer.show;
import static de.embl.cba.transforms.utils.Scalings.createRescaledArrayImg;
import static de.embl.cba.transforms.utils.Transforms.getScalingFactors;
import static java.lang.Math.toRadians;

public class FlyEmbryoTwoChannelRegistration< T extends RealType< T > & NativeType< T > >
{
	final FlyEmbryoRegistrationSettings settings;
	final OpService opService;
	private RandomAccessibleInterval< BitType > embryoMask;
	private double coverslipPosition;
	private AffineTransform3D transformAtRegistrationResolution;
	private Img< IntType > watershedLabelImg;
	private double[] correctedCalibration;
	private AffineTransform3D registration;
	private double[] registrationCalibration;
	private RandomAccessibleInterval< T > intensityCorrectedChannel2;
	private RandomAccessibleInterval< BitType > yawAndOrientationAlignedMask;
	private RandomAccessibleInterval< T > isotropicCh1;
	private RandomAccessibleInterval< T > isotropicCh2;
	private RandomAccessibleInterval< T > intensityCorrectedChannel1;
	private RandomAccessibleInterval< BitType > mask;
	private RandomAccessibleInterval< BitType > yawAlignedMask;
	private RandomAccessibleInterval yawAlignedIntensities;

	public FlyEmbryoTwoChannelRegistration( FlyEmbryoRegistrationSettings settings, OpService opService )
	{
		this.settings = settings;
		this.opService = opService;
	}

	public void run( RandomAccessibleInterval< T > ch1,
			  		 RandomAccessibleInterval< T > ch2,
			 		 double[] inputCalibration )
	{
		if ( settings.showIntermediateResults ) show( ch1, "input image ch1 ", null, inputCalibration, false );

		registration = new AffineTransform3D();

		refractiveIndexScalingCorrection( ch1, inputCalibration );

		createIsotropicImages( ch1, ch2 );

		refractiveIndexIntensityCorrection();

		if ( ! segmentEmbryo() ) return;

		yawAlignment();

		orientLongAxis();

		rollTransform();

		transformAtRegistrationResolution = registration;
	}

	public void rollTransform()
	{
		/**
		 *  Roll transform
		 */

		final AffineTransform3D rollTransform = computeRollTransform(
				registration,
				registrationCalibration,
				intensityCorrectedChannel2,
				yawAndOrientationAlignedMask,
				settings.rollAngleComputationMethod );

		rollTransform.rotate( X, Math.PI ); // this changes whether the found structure should be at the top or bottom

		registration = registration.preConcatenate( rollTransform  );

		if ( settings.showIntermediateResults )
			show( Transforms.createTransformedView( intensityCorrectedChannel1, registration ), "aligned at registration resolution", Transforms.origin(), registrationCalibration, false );
	}

	public void orientLongAxis()
	{
		/**
		 *  Long axis orientation
		 */

		Logger.log( "Computing long axis orientation..." );

		final AffineTransform3D orientationTransform = computeFlippingTransform( yawAlignedMask, yawAlignedIntensities, settings.registrationResolution );

		registration = registration.preConcatenate( orientationTransform );

		yawAndOrientationAlignedMask = Utils.copyAsArrayImg( Transforms.createTransformedView( embryoMask, registration, new NearestNeighborInterpolatorFactory() ) );

		if ( settings.showIntermediateResults ) show( yawAndOrientationAlignedMask, "long axis aligned and oriented", Transforms.origin(), registrationCalibration, false );
	}

	public void yawAlignment()
	{
		/**
		 * Compute ellipsoid (probably mainly yaw) alignment
		 * - https://en.wikipedia.org/wiki/Euler_angles
		 */

		Logger.log( "Fit ellipsoid..." );

		final EllipsoidMLJ ellipsoidParameters = EllipsoidsMLJ.computeParametersFromBinaryImage( embryoMask );

		registration.preConcatenate( EllipsoidsMLJ.createAlignmentTransform( ellipsoidParameters ) );

		yawAlignedMask = Utils.copyAsArrayImg( Transforms.createTransformedView( embryoMask, registration, new NearestNeighborInterpolatorFactory() ) );

		yawAlignedIntensities = Utils.copyAsArrayImg( Transforms.createTransformedView( isotropicCh1, registration ) );
	}

	public boolean segmentEmbryo()
	{

		createMask();

		/**
		 * Distance transform
		 * - Note: EUCLIDIAN distances are returned as squared distances
		 */

		Logger.log( "Distance transform..." );

		final RandomAccessibleInterval< DoubleType > distances = Algorithms.computeSquaredDistances( mask );

		if ( settings.showIntermediateResults )
			show( distances, "squared distances", null, registrationCalibration, false );


		/**
		 * Watershed seeds
		 * - if local maxima are defined as strictly larger (>) one misses them in case two
		 *   neighboring pixels in the centre of an object have the same distance value
		 * - if local maxima are >= and the search radius is only 1 pixel (four-connected) one gets false maxima at the corners objects
		 *   thus, the search radius should be always >= 2 pixels
		 * - triangle shaped appendices are an issue because they do not have a
		 *   maximum in the distance map
		 * - due to the elongated shape of the embryos there might not be a clear maximum => use also a global threshold
		 */

		final ImgLabeling< Integer, IntType > seedsLabelImg = createWatershedSeeds( distances );

		/**
		 * Watershed
		 */

		final ImgLabeling< Integer, IntType > watershedLabeling = computeWatershed( mask, distances, seedsLabelImg );

		if ( settings.showIntermediateResults )
			show( watershedLabelImg, "watershed", null, registrationCalibration, false );

		/**
		 * Get main embryo
		 * - TODO: replace by largest rather than central
		 */

		Logger.log( "Extract main embryo..." );

		final LabelRegion< Integer > centralObjectRegion = getCentralObjectLabelRegion( watershedLabeling );

		if ( centralObjectRegion == null ) return false;

		embryoMask = Algorithms.createMaskFromLabelRegion( centralObjectRegion, Intervals.dimensionsAsLongArray( isotropicCh1 ) );

		if ( settings.showIntermediateResults ) show( Utils.copyAsArrayImg( embryoMask ), "embryo mask", null, registrationCalibration, false );

		/**
		 * Process main embryo mask
		 * - TODO: put the currently hard-coded values into settings
		 */

		embryoMask = Algorithms.close( embryoMask, ( int ) ( 20.0 / settings.registrationResolution ) );

		// embryoMask = Algorithms.open( embryoMask, ( int ) ( 20.0 / settings.registrationResolution ) );

		if ( settings.showIntermediateResults )
			show( embryoMask, "embryo mask - processed",
					null, registrationCalibration, false );

		return true;
	}

	public void createMask()
	{
		/**
		 *  Compute threshold
		 *  - TODO: How does Huang work?
		 *  - TODO: find some more scientific method to determine threshold...
		 */

		final double huang = Algorithms.thresholdHuang( intensityCorrectedChannel1 );

		double thresholdAfterIntensityCorrection = huang;

		Logger.log( "Threshold (after intensity correction): " + thresholdAfterIntensityCorrection );

		/**
		 * Create mask
		 */

		mask = Algorithms.createMask( intensityCorrectedChannel1, thresholdAfterIntensityCorrection );

		if ( settings.showIntermediateResults )
			show( mask, "binary mask", null,
					registrationCalibration, false );


		/**
		 * Process mask
		 * - remove small objects
		 * - close holes
		 */

		Regions.removeSmallRegionsInMask( mask, settings.minimalObjectSize, settings.registrationResolution );

		for ( int d = 0; d < 3; ++d )
		{
			mask = Algorithms.fillHoles3Din2D( mask, d, opService );
		}

		if ( settings.showIntermediateResults ) show( mask, "small objects removed and holes closed", null, registrationCalibration, false );

	}

	public void refractiveIndexIntensityCorrection()
	{
		/**
		 *  Compute intensity offset (for refractive index mismatch corrections)
		 */

		Logger.log( "Offset and threshold..." );

		final IntensityHistogram downscaledSvbIntensityHistogram =
				new IntensityHistogram( isotropicCh1, 65535.0, 5.0 );

		CoordinateAndValue intensityHistogramMode = downscaledSvbIntensityHistogram.getMode();

		Logger.log( "Intensity offset: " + intensityHistogramMode.coordinate );


		/**
		 *  Compute approximate axial embryo center and coverslip coordinate
		 */

		final CoordinatesAndValues averageSvbIntensitiesAlongZ =
				Utils.computeAverageIntensitiesAlongAxis(
						isotropicCh1, 2, settings.registrationResolution );

		if ( settings.showIntermediateResults )
			Plots.plot(
					averageSvbIntensitiesAlongZ.coordinates,
					averageSvbIntensitiesAlongZ.values,
					"z [um]", "average intensities" );

		final double embryoCenterPosition =
				CurveAnalysis.maximum( averageSvbIntensitiesAlongZ ).coordinate;

		coverslipPosition =
				embryoCenterPosition - FlyEmbryoRegistrationSettings.drosophilaWidth / 2.0;

		Logger.log( "Approximate coverslip coordinate [um]: " + coverslipPosition );
		Logger.log( "Approximate axial embryo center coordinate [um]: " + embryoCenterPosition );

		/**
		 *  Refractive index corrections
		 */

		Logger.log( "Refractive index intensity correction..." );

		final RefractiveIndexMismatchCorrectionSettings correctionSettings = new RefractiveIndexMismatchCorrectionSettings();
		correctionSettings.intensityOffset = intensityHistogramMode.coordinate;
		correctionSettings.intensityDecayLengthMicrometer = settings.refractiveIndexIntensityCorrectionDecayLength;
		correctionSettings.coverslipPositionMicrometer = coverslipPosition;
		correctionSettings.pixelCalibrationMicrometer = settings.registrationResolution;

		intensityCorrectedChannel1 = Utils.copyAsArrayImg( isotropicCh1 );
		RefractiveIndexMismatchCorrections.correctIntensity( intensityCorrectedChannel1, correctionSettings );

		intensityCorrectedChannel2 = Utils.copyAsArrayImg( isotropicCh2 );
		RefractiveIndexMismatchCorrections.correctIntensity( intensityCorrectedChannel2, correctionSettings );

		if ( settings.showIntermediateResults ) show( intensityCorrectedChannel1, "intensity corrected channel 1", null, registrationCalibration, false );
	}

	public void createIsotropicImages( RandomAccessibleInterval< T > ch1, RandomAccessibleInterval< T > ch2 )
	{
		/**
		 *  Down-sampling to registration resolution
		 *  - Speeds up calculations ( pow(3) effect in 3D! )
		 *  - Reduces noise
		 *  - Fills "holes" in staining
		 *  - TODO: bug: during down-sampling saturated pixels become zero
		 */

		Logger.log( "Down-sampling to registration resolution..." );

		isotropicCh1 = createRescaledArrayImg(
				ch1,
				getScalingFactors( correctedCalibration, settings.registrationResolution ) );

		isotropicCh2 = createRescaledArrayImg(
				ch2,
				getScalingFactors( correctedCalibration, settings.registrationResolution ) );

		registrationCalibration = Utils.as3dDoubleArray( settings.registrationResolution );

		if ( settings.showIntermediateResults )
			show( isotropicCh1,
					"isotropic sampled at registration resolution",
					null, registrationCalibration, false );
	}

	public < T extends RealType< T > & NativeType< T > > void refractiveIndexScalingCorrection( RandomAccessibleInterval< T > svb, double[] inputCalibration )
	{
		/**
		 *  Axial calibration correction due to refractive index mismatch
		 *  - We are using an NA 0.8 air lens imaging into water/embryo (1.33 < NA < 1.51)
		 *  - Complicated topic: http://www.bio.brandeis.edu/marderlab/axial%20shift.pdf
		 *  - We assume axial compression by factor ~1.6
		 */

		Logger.log( "Refractive index scaling correction..." );

		correctedCalibration = RefractiveIndexMismatchCorrections.getAxiallyCorrectedCalibration( inputCalibration, settings.refractiveIndexAxialCalibrationCorrectionFactor );

		if ( settings.showIntermediateResults ) show( svb, "corrected calibration", null, correctedCalibration, false );
	}

	public ImgLabeling< Integer, IntType > computeWatershed( RandomAccessibleInterval< BitType > mask, RandomAccessibleInterval< DoubleType > distances, ImgLabeling< Integer, IntType > seedsLabelImg )
	{
		Logger.log( "Watershed..." );

		// prepare result label image
		watershedLabelImg = ArrayImgs.ints( Intervals.dimensionsAsLongArray( mask ) );
		final ImgLabeling< Integer, IntType > watershedLabeling = new ImgLabeling<>( watershedLabelImg );

		opService.image().watershed(
				watershedLabeling,
				Utils.invertedView( distances ),
				seedsLabelImg,
				false,
				false );

		Utils.applyMask( watershedLabelImg, mask );
		return watershedLabeling;
	}


	public double[] getCorrectedCalibration()
	{
		return correctedCalibration;
	}

	public double getCoverslipPosition()
	{
		return coverslipPosition;
	}

	public Img< IntType > getWatershedLabelImg()
	{
		return watershedLabelImg;
	}

	public AffineTransform3D getRegistrationTransform( double[] inputCalibration, double outputResolution )
	{
		final AffineTransform3D transform =
				Transforms.getScalingTransform( inputCalibration, settings.registrationResolution )
						.preConcatenate( transformAtRegistrationResolution.copy() )
						.preConcatenate( Transforms.getScalingTransform( settings.registrationResolution, outputResolution ) );

		return transform;
	}

	public RandomAccessibleInterval< BitType > createAlignedMask( double resolution, FinalInterval interval )
	{

		/**
		 * - TODO: using the mask just like this was cutting away signal from embryo..
		 * 		   the issue might be that during the rotations the voxels do not end up
		 * 		   precisely where they should be? Currently, I simple dilate "a bit".
		 * 		   Feels kind of messy...better way?
		 */

		Logger.log( "Creating aligned mask..." );

		final RandomAccessibleInterval< BitType > dilatedMask = Algorithms.dilate( embryoMask, 2 );

		AffineTransform3D transform = transformAtRegistrationResolution.copy()
				.preConcatenate( Transforms.getScalingTransform( settings.registrationResolution, resolution ) );

		RandomAccessibleInterval< BitType > alignedMask =
				Utils.copyAsArrayImg(
					Transforms.createTransformedView(
							dilatedMask,
							transform,
							interval, // after the transform we need to specify where we want to "crop"
							new NearestNeighborInterpolatorFactory() // binary image => do not interpolate linearly!
					)
				);

		if ( settings.showIntermediateResults ) show( alignedMask, "aligned mask at output resolution", Transforms.origin(), resolution );

		return alignedMask;
	}



	public < T extends RealType< T > & NativeType< T > > AffineTransform3D computeRollTransform(
			AffineTransform3D registration,
			double[] registrationCalibration,
			RandomAccessibleInterval< T > intensityCorrectedCh2,
			RandomAccessibleInterval< BitType > yawAndOrientationAlignedMask,
			String rollAngleComputationMethod )
	{
		Logger.log( "Computing roll transform, using method: " + rollAngleComputationMethod );

		if ( rollAngleComputationMethod.equals( FlyEmbryoRegistrationSettings.INTENSITY ) )
		{
			final RandomAccessibleInterval yawAndOrientationAlignedCh2 = Utils.copyAsArrayImg( Transforms.createTransformedView( intensityCorrectedCh2, registration.copy(), new NearestNeighborInterpolatorFactory() ) );

			final AffineTransform3D intensityBasedRollTransform = computeIntensityBasedRollTransform(
					yawAndOrientationAlignedCh2,
					settings.projectionXMin,
					settings.projectionXMax,
					settings.projectionBlurSigma,
					registrationCalibration );

			return intensityBasedRollTransform;

		}
		else if ( rollAngleComputationMethod.equals( FlyEmbryoRegistrationSettings.CENTROID_SHAPE_BASED_ROLL_TRANSFORM ) )
		{
			final CentroidsParameters centroidsParameters = Utils.computeCentroidsParametersAlongXAxis( yawAndOrientationAlignedMask, settings.registrationResolution, settings.rollAngleMaxDistanceToCenter );

			if ( settings.showIntermediateResults )
				Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.angles, "x", "angle" );
			if ( settings.showIntermediateResults )
				Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.distances, "x", "distance" );
			if ( settings.showIntermediateResults )
				Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.numVoxels, "x", "numVoxels" );
			if ( settings.showIntermediateResults )
				show( yawAndOrientationAlignedMask, "mask with centroids", centroidsParameters.centroids, registrationCalibration, false );

			final AffineTransform3D rollTransform = computeCentroidBasedRollTransform( centroidsParameters, settings );

			return rollTransform;

		}
		else if ( rollAngleComputationMethod.equals( FlyEmbryoRegistrationSettings.PROJECTION_SHAPE_BASED_ROLL_TRANSFORM ) )
		{

			final RandomAccessibleInterval< UnsignedIntType > intMask =
					Converters.convert( yawAndOrientationAlignedMask,
							( i, o ) -> o.set( i.getRealDouble() > 0 ? 1000 : 0 ),
							new UnsignedIntType() );

			final AffineTransform3D intensityBasedRollTransform = computeIntensityBasedRollTransform(
					intMask,
					intMask.min( X ) * settings.registrationResolution,
					intMask.max( X ) * settings.registrationResolution,
					12.0, registrationCalibration );

			return intensityBasedRollTransform;
		}

		return null;

	}

	public < T extends RealType< T > & NativeType< T > > double getThreshold( RandomAccessibleInterval< T > downscaled )
	{

		double threshold = 0;

		if ( settings.thresholdModality.equals( FlyEmbryoRegistrationSettings.HUANG_AUTO_THRESHOLD ) )
		{
			final Histogram1d< T > histogram = opService.image().histogram( Views.iterable( downscaled ) );

			double huang = opService.threshold().huang( histogram ).getRealDouble();
			double yen = opService.threshold().yen( histogram ).getRealDouble();

			threshold = huang;
		}
		else
		{
			threshold= settings.thresholdInUnitsOfBackgroundPeakHalfWidth;
		}
		return threshold;
	}

	public ImgLabeling< Integer, IntType > createWatershedSeeds( RandomAccessibleInterval< DoubleType > distance )
	{
		Logger.log( "Seeds for watershed...");

		double globalDistanceThreshold = Math.pow( settings.watershedSeedsGlobalDistanceThreshold / settings.registrationResolution, 2 );
		double localMaximaDistanceThreshold = Math.pow( settings.watershedSeedsLocalMaximaDistanceThreshold / settings.registrationResolution, 2 );
		int localMaximaSearchRadius = (int) ( settings.watershedSeedsLocalMaximaSearchRadius / settings.registrationResolution );

		final RandomAccessibleInterval< BitType >  seeds = Algorithms.createWatershedSeeds(
				distance,
				new HyperSphereShape( localMaximaSearchRadius ),
				globalDistanceThreshold,
				localMaximaDistanceThreshold );

		final ImgLabeling< Integer, IntType > seedsLabelImg = Regions.asImgLabeling( seeds, ConnectedComponents.StructuringElement.FOUR_CONNECTED );

		if ( settings.showIntermediateResults ) show( Utils.asIntImg( seedsLabelImg ), "watershed seeds", null, Utils.as3dDoubleArray( settings.registrationResolution ), false );

		return seedsLabelImg;
	}

	public AffineTransform3D computeFlippingTransform( RandomAccessibleInterval yawAlignedMask, RandomAccessibleInterval yawAlignedIntensities, double calibration )
	{
		final CoordinatesAndValues coordinatesAndValues = Utils.computeAverageIntensitiesAlongAxisWithinMask( yawAlignedIntensities, yawAlignedMask, X, calibration );

		if ( settings.showIntermediateResults ) Plots.plot( coordinatesAndValues.coordinates, coordinatesAndValues.values, "x", "average intensity" );

		CoordinateAndValue maximum = CurveAnalysis.maximum( coordinatesAndValues, null );

		AffineTransform3D affineTransform3D = new AffineTransform3D();

		if ( maximum.coordinate > 0 ) affineTransform3D.rotate( Z, toRadians( 180.0D ) );

		return affineTransform3D;
	}



	public < T extends RealType< T > & NativeType< T > >
	AffineTransform3D computeIntensityBasedRollTransform(
			RandomAccessibleInterval rai,
			double xMin,
			double xMax,
			double blurSigma,
			double[] registrationCalibration )
	{
		final RandomAccessibleInterval< T > longAxisProjection = Utils.createAverageProjectionAlongAxis(
				rai,
				X,
				xMin,
				xMax,
				settings.registrationResolution );

		if ( settings.showIntermediateResults ) show( longAxisProjection, "channel2 projection", null, registrationCalibration, false );

		final RandomAccessibleInterval< T > blurred = Utils.createBlurredRai(
				longAxisProjection,
				blurSigma,
				settings.registrationResolution );

		final RealPoint maximum = Algorithms.getMaximumLocation( blurred, Utils.as2dDoubleArray( settings.registrationResolution ));
		final List< RealPoint > realPoints = Utils.asRealPointList( maximum );
		realPoints.add( new RealPoint( new double[]{ 0, 0 } ) );

		if ( settings.showIntermediateResults ) show( blurred, "perpendicular projection - blurred ", realPoints, Utils.as2dDoubleArray( settings.registrationResolution ), false );

		final AffineTransform3D xAxisRollTransform = createXAxisRollTransform( maximum );

		return xAxisRollTransform;
	}

	public ArrayList< RealPoint > createTransformedCentroidPointList( CentroidsParameters centroidsParameters, AffineTransform3D rollTransform )
	{
		final ArrayList< RealPoint > transformedRealPoints = new ArrayList<>();

		for ( RealPoint realPoint : centroidsParameters.centroids )
		{
			final RealPoint transformedRealPoint = new RealPoint( 0, 0, 0 );
			rollTransform.apply( realPoint, transformedRealPoint );
			transformedRealPoints.add( transformedRealPoint );
		}
		return transformedRealPoints;
	}

	public static AffineTransform3D computeCentroidBasedRollTransform( CentroidsParameters centroidsParameters, FlyEmbryoRegistrationSettings settings )
	{
		final double rollAngle = computeRollAngle( centroidsParameters, settings.rollAngleMinDistanceToAxis, settings.rollAngleMinDistanceToCenter, settings.rollAngleMaxDistanceToCenter );

		Logger.log( "Roll angle " + rollAngle );

		AffineTransform3D rollTransform = new AffineTransform3D();

		rollTransform.rotate( X, toRadians( rollAngle ) );

		return rollTransform;
	}

	public static double computeRollAngle( CentroidsParameters centroidsParameters, double minDistanceToAxis, double minDistanceToCenter, double maxDistanceToCenter )
	{
		final int n = centroidsParameters.axisCoordinates.size();

		List< Double> offCenterAngles = new ArrayList<>(  );

		for ( int i = 0; i < n; ++i )
		{
			if ( ( centroidsParameters.distances.get( i ) > minDistanceToAxis ) &&
					( Math.abs(  centroidsParameters.axisCoordinates.get( i ) ) > minDistanceToCenter ) &&
					( Math.abs(  centroidsParameters.axisCoordinates.get( i ) ) < maxDistanceToCenter ))
			{
				offCenterAngles.add( centroidsParameters.angles.get( i ) );
			}
		}

		double medianAngle = Utils.median( offCenterAngles );

		return -medianAngle;
	}


	private Img< BitType > createMaskFromLabelRegion( LabelRegion< Integer > centralObjectRegion, long[] dimensions )
	{
		final Img< BitType > centralObjectImg = ArrayImgs.bits( dimensions );

		final Cursor< Void > regionCursor = centralObjectRegion.cursor();
		final net.imglib2.RandomAccess< BitType > access = centralObjectImg.randomAccess();
		while ( regionCursor.hasNext() )
		{
			regionCursor.fwd();
			access.setPosition( regionCursor );
			access.get().set( true );
		}
		return centralObjectImg;
	}

	private Img< UnsignedByteType > createUnsignedByteTypeMaskFromLabelRegion( LabelRegion< Integer > centralObjectRegion, long[] dimensions )
	{
		final Img< UnsignedByteType > centralObjectImg = ArrayImgs.unsignedBytes( dimensions );

		final Cursor< Void > regionCursor = centralObjectRegion.cursor();
		final net.imglib2.RandomAccess< UnsignedByteType > access = centralObjectImg.randomAccess();
		while ( regionCursor.hasNext() )
		{
			regionCursor.fwd();
			access.setPosition( regionCursor );
			access.get().set( 255 );
		}
		return centralObjectImg;
	}


	private LabelRegion< Integer > getCentralObjectLabelRegion( ImgLabeling< Integer, IntType > labeling )
	{
		int centralLabel = getCentralLabel( labeling );

		if ( centralLabel == -1 )
		{
			return null;
		}

		final LabelRegions< Integer > labelRegions = new LabelRegions<>( labeling );

		return labelRegions.getLabelRegion( centralLabel );
	}

	private static int getCentralLabel( ImgLabeling< Integer, IntType > labeling )
	{
		final net.imglib2.RandomAccess< LabelingType< Integer > > labelingRandomAccess = labeling.randomAccess();
		for ( int d : XYZ ) labelingRandomAccess.setPosition( labeling.dimension( d ) / 2, d );
		int centralIndex = labelingRandomAccess.get().getIndex().getInteger();

		if ( centralIndex > 0 )
		{
			return labeling.getMapping().labelsAtIndex( centralIndex ).iterator().next();
		}
		else
		{
			return -1;
		}
	}

	public static < T extends RealType< T > & NativeType< T > >
	AffineTransform3D createXAxisRollTransform( RealPoint maximum2DinYZPlane )
	{
		double angleToZAxisInDegrees = Angles.angle2DToCoordinateSystemsAxisInDegrees( maximum2DinYZPlane );
		AffineTransform3D rollTransform = new AffineTransform3D();

		Logger.log( "Roll angle: " + angleToZAxisInDegrees );
		rollTransform.rotate( X, toRadians( angleToZAxisInDegrees ) );

		return rollTransform;
	}

	//
	// Useful code snippets
	//

	/**
	 *  Distance transformAllChannels

	 Hi Christian

	 yes, it seems that you were doing the right thing (as confirmed by your
	 visual inspection of the result). One thing to note: You should
	 probably use a DoubleType image with 1e20 and 0 values, to make sure
	 that f(q) is larger than any possible distance in your image. If you
	 choose 255, your distance is effectively bounded at 255. This can be an
	 issue for big images with sparse foreground objects. With squared
	 Euclidian distance, 255 is already reached if a background pixels is
	 further than 15 pixels from a foreground pixel! If you use
	 Converters.convert to generate your image, the memory consumption
	 remains the same.


	 Phil

	 final RandomAccessibleInterval< UnsignedByteType > binary = Converters.convert(
	 downscaled, ( i, o ) -> o.set( i.getRealDouble() > settings.thresholdInUnitsOfBackgroundPeakHalfWidth ? 255 : 0 ), new UnsignedByteType() );

	 if ( settings.showIntermediateResults ) show( binary, "binary", null, calibration, false );


	 final RandomAccessibleInterval< DoubleType > distance = ArrayImgs.doubles( Intervals.dimensionsAsLongArray( binary ) );

	 DistanceTransform.transformAllChannels( binary, distance, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN );


	 final double maxDistance = Algorithms.getMaximumValue( distance );

	 final RandomAccessibleInterval< IntType > invertedDistance = Converters.convert( distance, ( i, o ) -> {
	 o.set( ( int ) ( maxDistance - i.get() ) );
	 }, new IntType() );

	 if ( settings.showIntermediateResults ) show( invertedDistance, "distance", null, calibration, false );

	 */


	/**
	 * Convert ImgLabelling to Rai

	 final RandomAccessibleInterval< IntType > labelMask =
	 Converters.convert( ( RandomAccessibleInterval< LabelingType< Integer > > ) watershedImgLabeling,
	 ( i, o ) -> {
	 o.set( i.getIndex().getInteger() );
	 }, new IntType() );

	 */




}
