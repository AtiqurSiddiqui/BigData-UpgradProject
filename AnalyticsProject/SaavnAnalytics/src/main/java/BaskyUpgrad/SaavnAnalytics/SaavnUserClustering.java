package BaskyUpgrad.SaavnAnalytics;

import static org.apache.spark.sql.functions.col;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.ml.clustering.KMeans;
import org.apache.spark.ml.clustering.KMeansModel;
import org.apache.spark.ml.feature.StringIndexer;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.ml.linalg.VectorUDT;
import org.apache.spark.ml.linalg.Vectors;
import org.apache.spark.ml.recommendation.ALS;
import org.apache.spark.ml.recommendation.ALSModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;

import scala.collection.Seq;


public class SaavnUserClustering 
{
	public static class songMeta implements Serializable {
	    private String[] artistIds;
	    private String songId;

	    public String getSongId() {
	      return songId;
	    }   

	    public void setSongId(String sId) {
	      this.songId = sId;
	    }   

	    public String[] getArtistIds() {
	      return artistIds;
	    }   

	    public void setArtistIds(String[] aIds) {
	      this.artistIds = aIds;
	    }   
	}
	public static SparkSession sparkSession;
	
    public static void main( String[] args )
    {
		
		Logger.getLogger("org").setLevel(Level.OFF);
		Logger.getLogger("akka").setLevel(Level.OFF);
		
		// setting up connection with spark
		System.setProperty("hadoop.home.dir", "C:\\spark\\bin");
		sparkSession = SparkSession.builder()
				.appName("SaavnAnalytics")
				.master("local[2]")
				.getOrCreate();

		//Read user click stream data
		String profilePath = "data/sample1.txt";
		//String profilePath = "data/sample100mb.csv";
		Dataset<Row> userProfile =
				sparkSession.read().format("csv").
				option("header","false").load(profilePath).
				toDF("UserId", "TimeStamp", "SongId", "Date");
		
		System.out.println("Original: Columns & Count of csv: "+userProfile.columns().length+" : "+userProfile.count());
		
		userProfile.show(false);
		
		String[] dropcol1 = {"TimeStamp","Date"};
		for(String col : dropcol1) {
			userProfile = userProfile.drop(col);
		}
		
		System.out.println("After dropping: Columns & Count: "+userProfile.columns().length+" : "+userProfile.count());
		userProfile.show(false);
		
		userProfile = userProfile.na().drop();
		System.out.println("Cleaning Stage: Columns & Count: "+userProfile.columns().length+" : "+userProfile.count());
		
		//Get the song frequency per user by groupby operation		
		Dataset<Row> userRatings = 
				userProfile.groupBy("UserId", "SongId")
				.count()
				.toDF("UserId", "SongId", "Frequency");

		System.out.println("Groupby User Count: "+userRatings.count());
		//Table columns - UserId, SongId, Frequency
		userRatings.show(100,false);
		

		Dataset<Row> userIdTable = userProfile.groupBy("UserId").count();
		System.out.println("Unique User Count: "+userIdTable.count());
		
		userIdTable.show(100,false);
				
		//Create UserIndex(Integer Type) for string UserId column to use in ALS model
		StringIndexer indexer = new StringIndexer()
				.setInputCol("UserId")
				.setOutputCol("UserIndex");

		//Table columns - UserId, SongId, Frequency, UserIndex
		Dataset<Row> userIndexed = indexer.fit(userRatings).transform(userRatings);
			
		userIndexed.show(10,false);

		//Create SongIndex(Integer Type) for string SongId column to use in ALS model
		indexer.setInputCol("SongId").setOutputCol("SongIndex");

		//Table columns - UserId, SongId, Frequency, UserIndex
		Dataset<Row> songIndexed =
				indexer.fit(userIndexed).transform(userIndexed);
		
		songIndexed.show(10,false);
		
		//Cast UserIndex, SongIndex to Interger Type to use in ALS model
		Dataset<Row> modelIndexed = songIndexed
				.withColumn("UserIndex", col("UserIndex").cast(DataTypes.IntegerType))
				.withColumn("SongIndex", col("SongIndex").cast(DataTypes.IntegerType));
	
		System.out.println("ModelIndexed row Count: "+modelIndexed.count());
		modelIndexed.agg(functions.min(modelIndexed.col("UserIndex")), 
				functions.max(modelIndexed.col("UserIndex")),
				functions.min(modelIndexed.col("SongIndex")),
				functions.max(modelIndexed.col("SongIndex"))).show();
		
				
		System.out.println("ModelIndexed filtered row Count: "+modelIndexed.count());
		
		ALS als = new ALS()
				  .setRank(10)
				  .setMaxIter(5)
				  .setRegParam(0.01)
				  .setUserCol("UserIndex")
				  .setItemCol("SongIndex")
				  .setRatingCol("Frequency");
		ALSModel model = als.fit(modelIndexed);
		
		Dataset<Row> userALSFeatures = model.userFactors();

		System.out.println("Features: Columns & Count: "+userALSFeatures.columns().length+" : "+userALSFeatures.count());
		System.out.println("item: "+model.getItemCol());
		System.out.println("item: "+ model.getUserCol());
		
		
		
		userALSFeatures.show(10,false);
		
		modelIndexed = modelIndexed.drop("SongIndex","SongId","Frequency").groupBy("UserId","UserIndex").count();
		modelIndexed = modelIndexed.drop("count");
		System.out.println("ModelIndexed filtered row Count: "+modelIndexed.count());
		modelIndexed.orderBy("UserIndex").show(100,false);
		
		Dataset<Row> userAlsFeature = 
				modelIndexed.join(userALSFeatures, modelIndexed.col("UserIndex").equalTo(userALSFeatures.col("id"))).drop("id");
		
		System.out.println("userAlsFeature Grouped row Count: "+userAlsFeature.count());
		userAlsFeature.show(100,false);
		userAlsFeature.printSchema();
		
		sparkSession.udf().register("ATOV", new UDF1<Seq<Float>, Vector>() {
			  /**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			@Override
			  public Vector call(Seq<Float> t1) {
				  List<Float> L = scala.collection.JavaConversions.seqAsJavaList(t1);
				    double[] DoubleArray = new double[t1.length()]; 
				    for (int i = 0 ; i < L.size(); i++) { 
				      DoubleArray[i]=L.get(i); 
				    } 
				    return Vectors.dense(DoubleArray); 
			  }
		}, new VectorUDT());
		
		Dataset<Row> userAlsFeatureVect = 
				userAlsFeature.withColumn("featuresVect", functions.callUDF("ATOV", userAlsFeature.col("features"))).drop("features");
		
		userAlsFeatureVect = userAlsFeatureVect.toDF("UserId", "UserIndex", "features");
		
		userAlsFeatureVect.printSchema();
		
		userAlsFeatureVect.show(100,false);
		/*
		// Scale the variables
	    StandardScaler scaler = new StandardScaler()
	    		  .setInputCol("features")
	    		  .setOutputCol("scaledFeatures")
	    		  .setWithStd(true)
	    		  .setWithMean(true);

	    		// Compute summary statistics by fitting the StandardScaler
	    		StandardScalerModel scalerModel = scaler.fit(finalData);

	    		// Normalize each feature to have unit standard deviation.
	    		Dataset<Row> scaledData = scalerModel.transform(finalData);
	    		scaledData.show();
		*/
		// Trains a k-means model, given array of k's
		List<Integer> numClusters = Arrays.asList(2,4,6,8,10,12,14,16);
		for (Integer k : numClusters) {
			KMeans kmeans = new KMeans().setK(k).setSeed(1L);
			KMeansModel modelk = kmeans.fit(userAlsFeatureVect);
		
			//Within Set Sum of Square Error (WESSE).					
			double WSSSE = modelk.computeCost(userAlsFeatureVect);
			System.out.println("WSSSE = " + WSSSE);
			
			//Shows the results
			Vector[] centers = modelk.clusterCenters();
			System.out.println("Cluster Centers: ");
			for (Vector center: centers) {
			  System.out.println(center);
			}
								
		}
		
		KMeans kmeansFinal = new KMeans().setK(6).setSeed(1L);
		KMeansModel modelFinal = kmeansFinal.fit(userAlsFeatureVect);
		/*
		Dataset<Row> testDataVector = assembler.transform(data_grouped);
		//Compute summary statistics by fitting the StandardScaler
		StandardScalerModel scalerModelTest = scaler.fit(testDataVector);
		
		//Normalize each feature to have unit standard deviation
		Dataset<Row> scaledDataTest = scalerModelTest.transform(testDataVector);
		scaledDataTest.show();*/
	
		// Make Predictions
		Dataset<Row> predictionsTest = modelFinal.transform(userAlsFeatureVect);
		predictionsTest.show();
		
		/*Dataset<Row> userALSFeatures = model.userFactors().drop("features").withColumn("id", col("id").cast(DataTypes.StringType));
		DataFrameWriter<Row> writer = userALSFeatures.write();
		writer.text("/Users/baskars/Details/NextStep/BigData/Upgrad/Project/AnalyticsAssignment/als.txt");*/
    }
}





/*
 * String songMetaDataPath = "data/metadata.csv";
		Dataset<Row> metaData = sparkSession.read().text(songMetaDataPath);
		metaData.show(50,false);		
		
		String songMetaDataPath1 = "data/metadata.csv";
		JavaRDD<songMeta> songMetaRDD = sparkSession.read().textFile(songMetaDataPath1).javaRDD()
			.map(line -> {
				String[] data1 = line.split(",");
				songMeta sm = new songMeta();
				sm.setSongId(data1[0]);
				sm.setArtistIds(Arrays.copyOfRange(data1, 1, data1.length));
				return sm;
			});
			
		
		JavaRDD<Integer> alen = songMetaRDD.map(s -> {
			return s.getArtistIds().length;
		});
		
		List<Integer> len = alen.collect();
		int max = 0;
		for(int l: len) {
			if (l > max)
				max = l;
			
		}
		System.out.println(max);
		
		Dataset<Row> songMetaDF = sparkSession.createDataFrame(songMetaRDD, songMeta.class);
		
		
		songMetaDF.show(50, false);
*/
