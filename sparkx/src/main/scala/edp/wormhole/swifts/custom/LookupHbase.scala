/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.swifts.custom

import edp.wormhole.common._
import edp.wormhole.common.SparkSchemaUtils.ums2sparkType
import edp.wormhole.sinks.hbasesink.HbaseConnection
import edp.wormhole.spark.log.EdpLogging
import edp.wormhole.swifts.parse.SwiftsSql
import edp.wormhole.ums.UmsFieldType.umsFieldType
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object LookupHbase extends EdpLogging {

  def transform(session: SparkSession, df: DataFrame, sqlConfig: SwiftsSql, sourceNamespace: String, sinkNamespace: String, connectionConfig: ConnectionConfig): DataFrame = {
    val selectFields: Array[(String, String)] = sqlConfig.fields.get.split(",").map(field => {
      val fields = field.split(":")
      (fields(0).trim, fields(1).trim)
    })

    val fromIndex = sqlConfig.sql.indexOf(" from ")
    val table2cfGrp = sqlConfig.sql.substring(fromIndex + 6, sqlConfig.sql.indexOf(")", fromIndex)).split("\\(")
    logInfo("table2cfGrp:" + table2cfGrp(0) + "," + table2cfGrp(1))
    val tablename = table2cfGrp(0)
    val cf = table2cfGrp(1)
    val patternContentList: mutable.Seq[RowkeyPatternContent] = RowkeyTool.parse(sqlConfig.sourceTableFields.get(0))

//    val keyOpts = ListBuffer.empty[(String,String)]
//    while (joinContent.contains("(")) {
//      val firstIndex = joinContent.indexOf("(")
//      val keyOpt = joinContent.substring(0, firstIndex).trim
//      val lastIndex = joinContent.lastIndexOf(")")
//      joinContent = joinContent.substring(firstIndex+1,lastIndex)
//      val param = if(joinContent.trim.endsWith(")")){//无参数
//        null.asInstanceOf[String]
//      }else{
//        if(joinContent.contains("(")){
//          val subLastIndex = joinContent.lastIndexOf(")",lastIndex)
//          val part = joinContent.substring(subLastIndex+1)
//          joinContent = joinContent.substring(0,subLastIndex+1)
//          if(part.contains(",")) part.trim.substring(1)
//          else null.asInstanceOf[String]
//        }else if(joinContent.contains(",")){
//          val tmpIndex = joinContent.indexOf(",")
//          val tmp = joinContent.substring(tmpIndex+1)
//          joinContent = joinContent.substring(0,tmpIndex)
//          tmp
//        }else null.asInstanceOf[String]
//      }
//      keyOpts += ((keyOpt.toLowerCase,param))
//    }
//    val joinbyFileds = joinContent.split("\\+")

    val resultSchema = {
      var resultSchema: StructType = df.schema
      val addColumnType = selectFields.map { case (name, dataType) =>
        StructField(name, ums2sparkType(umsFieldType(dataType)))
      }
      addColumnType.foreach(column => resultSchema = resultSchema.add(column))
      resultSchema
    }

    val resultData = ListBuffer.empty[Row]
    val joinedRow: RDD[Row] = df.rdd.mapPartitions(partition => {

      val originalData: ListBuffer[Row] = partition.to[ListBuffer]
      if (originalData.nonEmpty) {
        val keyFieldsSchema = RowkeyTool.generateKeyFieldsSchema(originalData,patternContentList)

        val keys: mutable.Seq[String] = originalData.map(row => {
          val keydatas = RowkeyTool.generateRowKeyDatas(keyFieldsSchema,row)
          RowkeyTool.generatePatternKey(keydatas,patternContentList)
        })

        HbaseConnection.initHbaseConfig(null, null, connectionConfig)
        val (ips, port, _) = HbaseConnection.getZookeeperInfo(connectionConfig.connectionUrl)

        val hbaseDatas = HbaseConnection.getDatasFromHbase(tablename, cf, true,keys, selectFields, ips, port)

        for (i <- originalData.indices) {
          val ori = originalData(i)
          val originalArray: Array[Any] = ori.schema.fieldNames.map(name => ori.get(ori.fieldIndex(name)))

          val key = keys(i)
          val hbaseData: Map[String, Any] = if (hbaseDatas.contains(key)) hbaseDatas(key) else null.asInstanceOf[Map[String, Any]]
          val dbOutputArray = selectFields.map { case (name, dataType) =>
            if (hbaseData==null || hbaseData.isEmpty) SparkSchemaUtils.s2sparkValue(null, umsFieldType(dataType))
            else SparkSchemaUtils.s2sparkValue(hbaseData(name).toString, umsFieldType(dataType))
          }

          val row = new GenericRowWithSchema(originalArray ++ dbOutputArray, resultSchema)
          resultData.append(row)
        }
      }
      resultData.toIterator
    })
    session.createDataFrame(joinedRow, resultSchema)
  }

}