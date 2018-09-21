package ch.epfl.lts2.wikipedia

import java.time._
import java.time.format.DateTimeFormatter
import java.nio.file.Paths
import org.rogach.scallop._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SQLContext, Row, DataFrame, SparkSession, Dataset}
import org.apache.spark.{SparkConf, SparkContext}


class PagecountConf(args: Seq[String]) extends ScallopConf(args) {
  val basePath = opt[String](required = true, name= "basePath")
  val outputPath = opt[String](required = true, name="outputPath")
  val startDate = opt[LocalDate](required = true, name="startDate")(singleArgConverter[LocalDate](LocalDate.parse(_)))
  val endDate = opt[LocalDate](required = true, name="endDate")(singleArgConverter[LocalDate](LocalDate.parse(_)))
  val pageDump = opt[String](name="pageDump")
  val minDailyVisit = opt[Int](name="minDailyVisit", default=Some[Int](100))
  verify()
}

class PagecountProcessor {
  lazy val sconf = new SparkConf().setAppName("Wikipedia pagecount processor").setMaster("local[*]")
  lazy val session = SparkSession.builder.config(sconf).getOrCreate()
  val parser = new WikipediaPagecountParser
  def dateRange(from: LocalDate, to: LocalDate, step: Period) : Iterator[LocalDate] = {
     Iterator.iterate(from)(_.plus(step)).takeWhile(!_.isAfter(to))
  }
  
  def parseLines(input: RDD[String], minDailyVisits:Int):DataFrame = {
    session.createDataFrame(parser.getRDD(input.filter(!_.startsWith("#"))).filter(w => w.dailyVisits > minDailyVisits))
  }
  
  def mergePagecount(pageDf:DataFrame, pagecountDf:DataFrame): DataFrame = {
    pagecountDf.join(pageDf, Seq("title", "namespace"))
  }
}

object PagecountProcessor {
  val pgCountProcessor = new PagecountProcessor
  val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  def main(args:Array[String]) = {
    val cfg = new PagecountConf(args)
    
    val range = pgCountProcessor.dateRange(cfg.startDate(), cfg.endDate(), Period.ofDays(1))
    val files = range.map(d => (d, Paths.get(cfg.basePath(), "pagecounts-" + d.format(dateFormatter) + ".bz2").toString)).toMap
    val pgInputRdd = files.mapValues(p => pgCountProcessor.session.sparkContext.textFile(p))
    
    
    val pcDf = pgInputRdd.mapValues(p => pgCountProcessor.parseLines(p, cfg.minDailyVisit()))
    
    if (cfg.pageDump.supplied) {
      val pageParser = new DumpParser
      val pgDf = pageParser.processFileToDf(pgCountProcessor.session, cfg.pageDump(), WikipediaDumpType.Page).select("id", "namespace", "title")
      
      // join page and page count
      val pcDf_id = pcDf.mapValues(pcdf => pgCountProcessor.mergePagecount(pgDf, pcdf))
      val dummy = pcDf_id.map(pc_id => pageParser.writeCsv(pc_id._2, Paths.get(cfg.outputPath(), pc_id._1.format(dateFormatter)).toString))
    }
  }
}