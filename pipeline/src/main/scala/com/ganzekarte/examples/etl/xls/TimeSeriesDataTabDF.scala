package com.ganzekarte.examples.etl.xls

import com.crealytics.spark.excel.ExcelDataFrameReader
import com.ganzekarte.examples.etl.xls.FieldTransformationDefinitions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object TimeSeriesDataTabDF {
  type DF = DataFrame

  /**
   * Processes time series data from given Excel tabs and merges them into a single dataframe.
   *
   * @param path       The path to the Excel file.
   * @param targetTabs The list of tab names to process.
   * @param spark      Implicit Spark session instance.
   * @return A dataframe with processed time series data.
   */
  def buildDF(path: String, targetTabs: Seq[String])(implicit spark: SparkSession) = {
    // Process each target tab and return a sequence of dataframes.
    val dfTabs = targetTabs.map { tabName =>
      val dfForTab = xlsFromPath(path, tabName)
      val builder = new TimeSeriesDataTabDF(dfForTab)
        .withChecksumColumn
        .withOnlyTemporalColumns
        .withColumnsMelted(tabName)

      // Return the dataframe representation of the processed data.
      builder.dataframe()
    }

    // Merge all processed dataframes into one, using "checksum" and "date" as joining keys.
    val reduced = dfTabs.reduce((l, r) => l.join(r, Seq("checksum", "date")))
    // Generate dynamic aggregation based on the provided column list
    val aggregations = targetTabs.map(colName => sum(colName).as(colName))
    val grouped = reduced.groupBy("date", "checksum").agg(aggregations.head, aggregations.tail: _*)
    new TimeSeriesDataTabDF(grouped)
  }

  def xlsFromPath(path: String, tabName: String)(implicit spark: SparkSession): DataFrame = {
    val peekDF = spark.read
      .option("locale", "en-US")
      .excel(
        header = true,
        dataAddress = tabName + "!",
        inferSchema = false,
        usePlainNumberFormat = false,
        maxRowsInMemory = 100000000,
        maxByteArraySize = 100000000,
      ).load(path)

    val schema = schemaFromDF(peekDF)

    val df = spark.read
      .option("locale", "en-US")
      .excel(
        header = true,
        dataAddress = tabName + "!",
        inferSchema = false,
        usePlainNumberFormat = false,
        maxRowsInMemory = 100000000,
        maxByteArraySize = 100000000,
      ).schema(schema).load(path)

    df
  }

  def schemaFromDF(df: DataFrame): StructType = {
    val fields = df.columns.map { header =>
      FieldDefinitions.find(_.excelTabName == header) match {
        case Some(field) =>
          val dataType = field.dataType match {
            case "FloatType" => FloatType
            case "IntegerType" | "LongType" => LongType
            case _ => StringType
          }
          StructField(header, dataType, nullable = true)
        case None =>
          StructField(header, StringType, nullable = true)
      }
    }
    StructType(fields)
  }

  def excelSerialNumberToDateTime(serial: Double): String = {
    val excelEpoch = LocalDate.of(1900, 1, 1)
    var days = serial.toInt

    // Adjust for the leap year bug in Excel
    if (days > 59) days -= 1

    val datePart =
      excelEpoch.plusDays(days - 1) // -2 because excel date starts from 1 not 0

    val formatter = DateTimeFormatter.ofPattern("MM_yyyy")
    datePart.format(formatter)
  }
}

class TimeSeriesDataTabDF(df: TimeSeriesDataTabDF.DF) {
  /**
   * Adds a checksum column to the dataframe for later cross-referencing.
   *
   * @return Instance of TimeSeriesDataTabDF with the added checksum column.
   */
  def withChecksumColumn: TimeSeriesDataTabDF = {
    //    println(df.columns.toSeq)
    val filteredColumns = df.columns.filter(ChecksumFields.map(_.excelTabName).contains(_)).sorted
    //    println(s"using ${filteredColumns.mkString(",")} for checksum")
    val concatenatedColumns = concat_ws("", filteredColumns.map(col): _*)
    val checksum = md5(concatenatedColumns)
    new TimeSeriesDataTabDF(df.withColumn("checksum", checksum))
  }

  /**
   * Removes columns that aren't relevant to the time series analysis.
   *
   * @return Instance of TimeSeriesDataTabDF with irrelevant columns dropped.
   */
  def withOnlyTemporalColumns: TimeSeriesDataTabDF = {
    // We don't have any of the other columns defined in our field definition file, so we can just filter these out
    val tabsToDrop = FieldTransformationDefinitions.FieldDefinitions
      .map(_.excelTabName)

    val columnsToDrop = df.columns.toSeq.filter(tabsToDrop.contains(_))
    new TimeSeriesDataTabDF(
      df.drop(columnsToDrop: _*)
    )
  }

  /**
   * Transforms columns of date data into rows with the format: date, value.
   *
   * @return Instance of TimeSeriesDataTabDF with reshaped data.
   */
  def withColumnsMelted(valueColumnName: String): TimeSeriesDataTabDF = {
    val dateColumns = df.columns.filterNot(colName =>
      FieldDefinitions.filter(_.isShared).map(_.excelTabName).contains(colName) || colName == "checksum"
    )
    val stackExpr = dateColumns.map(col => s"'$col', `$col`").mkString(", ")
    new TimeSeriesDataTabDF(
      df.selectExpr(
        "checksum",
        s"stack(${dateColumns.length}, $stackExpr) as (date, ${valueColumnName.toLowerCase})"
      )
    )
  }


  /**
   * Accessor method for the internal dataframe.
   *
   * @return Internal dataframe.
   */
  def dataframe(): TimeSeriesDataTabDF.DF = {
    df
  }
}

