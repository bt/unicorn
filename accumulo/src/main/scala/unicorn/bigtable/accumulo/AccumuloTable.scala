/*******************************************************************************
 * (C) Copyright 2015 ADP, LLC.
 *   
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package unicorn.bigtable.accumulo

import scala.collection.JavaConversions._
import org.apache.hadoop.io.Text
import org.apache.accumulo.core.client.{BatchWriterConfig, ScannerBase}
import org.apache.accumulo.core.data.{Mutation, Range}
import org.apache.accumulo.core.security.{Authorizations, ColumnVisibility => CellVisibility}
import unicorn.bigtable._
import unicorn.util._

/** Accumulo table adapter.
  *
  * @author Haifeng Li
  */
class AccumuloTable(val db: Accumulo, val name: String) extends BigTable with RowScan with CellLevelSecurity {
  override def close: Unit = () // Connector has no close method

  override val columnFamilies = db.tableOperations.getLocalityGroups(name).map(_._1).toSeq

  override val startRowKey: ByteArray = null
  override val endRowKey: ByteArray = null

  var cellVisibility = new CellVisibility
  var authorizations = new Authorizations

  override def setCellVisibility(expression: String): Unit = {
    cellVisibility = new CellVisibility(expression)
  }

  override def getCellVisibility: String = {
    new String(cellVisibility.getExpression)
  }

  override def setAuthorizations(labels: String*): Unit = {
    authorizations = new Authorizations(labels: _*)
  }

  override def getAuthorizations: Seq[String] = {
    authorizations.getAuthorizations.map { bytes => new String(bytes)}
  }

  override def apply(row: ByteArray, family: String, column: ByteArray): Option[ByteArray] = {
    val scanner = newScanner
    scanner.setRange(new Range(new Text(row)))
    scanner.fetchColumn(new Text(family), new Text(column))
    val iterator = scanner.iterator
    if (iterator.hasNext) Option(iterator.next.getValue.get)
    else None
  }

  private def getColumns(scanner: ScannerBase, family: String, columns: Seq[ByteArray]): Unit = {
    val familyText = new Text(family)
    if (columns.isEmpty)
      scanner.fetchColumnFamily(familyText)
    else
      columns.foreach { column => scanner.fetchColumn(familyText, new Text(column)) }
  }

  override def get(row: ByteArray, families: Seq[(String, Seq[ByteArray])]): Seq[ColumnFamily] = {
    val scanner = newScanner
    scanner.setRange(new Range(new Text(row)))
    families.foreach { case (family, columns) => getColumns(scanner, family, columns) }
    val rowScanner = new AccumuloRowScanner(scanner)
    if (rowScanner.hasNext) rowScanner.next.families else Seq.empty
  }

  override def get(row: ByteArray, family: String, columns: ByteArray*): Seq[Column] = {
    val scanner = newScanner
    scanner.setRange(new Range(new Text(row)))
    getColumns(scanner, family, columns)

    scanner.map { cell =>
      Column(cell.getKey.getColumnQualifier.copyBytes, cell.getValue.get, cell.getKey.getTimestamp)
    }.toSeq
  }

  override def getBatch(rows: Seq[ByteArray], families: Seq[(String, Seq[ByteArray])]): Seq[Row] = {
    val scanner = newBatchScanner(numBatchThreads(rows))
    val ranges = rows.map { row => new Range(new Text(row)) }
    scanner.setRanges(ranges)
    families.foreach { case (family, columns) => getColumns(scanner, family, columns) }
    val rowScanner = new AccumuloRowScanner(scanner)
    rowScanner.toSeq
  }

  override def getBatch(rows: Seq[ByteArray], family: String, columns: ByteArray*): Seq[Row] = {
    val scanner = newBatchScanner(numBatchThreads(rows))
    val ranges = rows.map { row => new Range(new Text(row)) }
    scanner.setRanges(ranges)

    if (columns.isEmpty)
      scanner.fetchColumnFamily(new Text(family))
    else
      columns.foreach { column => scanner.fetchColumn(new Text(family), new Text(column)) }

    val rowScanner = new AccumuloRowScanner(scanner)
    rowScanner.toSeq
  }

  override def scan(startRow: ByteArray, stopRow: ByteArray, families: Seq[(String, Seq[ByteArray])]): RowScanner = {
    val scanner = newScanner
    // from startRow inclusive to endRow exclusive.
    scanner.setRange(new Range(rowKey(startRow), true, rowKey(stopRow), false))
    families.foreach { case (family, columns) =>
      if (columns.isEmpty)
        scanner.fetchColumnFamily(new Text(family))
      else
        columns.foreach { column => scanner.fetchColumn(new Text(family), new Text(column)) }
    }

    new AccumuloRowScanner(scanner)
  }

  override def scan(startRow: ByteArray, stopRow: ByteArray, family: String, columns: ByteArray*): RowScanner = {
    val scanner = newScanner
    scanner.setRange(new Range(rowKey(startRow), rowKey(stopRow)))
    if (columns.isEmpty)
      scanner.fetchColumnFamily(new Text(family))
    else
      columns.foreach { column => scanner.fetchColumn(new Text(family), new Text(column)) }

    new AccumuloRowScanner(scanner)
  }

  override def put(row: ByteArray, family: String, column: ByteArray, value: ByteArray, timestamp: Long): Unit = {
    val mutation = new Mutation(row)
    if (timestamp != 0)
      mutation.put(family, column, cellVisibility, timestamp, value)
    else
      mutation.put(family, column, cellVisibility, value)

    val writer = newBatchWriter()
    writer.addMutation(mutation)
    writer.flush
  }

  override def put(row: ByteArray, family: String, columns: Column*): Unit = {
    val mutation = new Mutation(row)
    columns.foreach { case Column(qualifier, value, timestamp) =>
      if (timestamp == 0)
        mutation.put(family, qualifier, cellVisibility, value)
      else
        mutation.put(family, qualifier, cellVisibility, timestamp, value)
    }

    val writer = newBatchWriter()
    writer.addMutation(mutation)
    writer.flush
  }

  override def put(row: ByteArray, families: Seq[ColumnFamily]): Unit = {
    val mutation = new Mutation(row)
    families.foreach { case ColumnFamily(family, columns) =>
      columns.foreach { case Column(qualifier, value, timestamp) =>
        if (timestamp == 0)
          mutation.put(family, qualifier, cellVisibility, value)
        else
          mutation.put(family, qualifier, cellVisibility, timestamp, value)
      }
    }

    val writer = newBatchWriter()
    writer.addMutation(mutation)
    writer.flush
  }

  override def putBatch(rows: Row*): Unit = {
    val writer = newBatchWriter()
    rows.foreach { case Row(row, families) =>
      val mutation = new Mutation(row)

      families.foreach { case ColumnFamily(family, columns) =>
        columns.foreach { case Column(qualifier, value, timestamp) =>
          if (timestamp == 0)
            mutation.put(family, qualifier, cellVisibility, value)
          else
            mutation.put(family, qualifier, cellVisibility, timestamp, value)
        }
      }

      writer.addMutation(mutation)
    }

    writer.flush
  }

  override def delete(row: ByteArray, family: String, columns: ByteArray*): Unit = {
    if (columns.isEmpty) {
      val range = Range.exact(new Text(row), new Text(family))
      val deleter = newBatchDeleter(1)
      deleter.setRanges(Seq(range))
      deleter.delete
    } else {
      val writer = newBatchWriter(1)
      val mutation = new Mutation(row)
      columns.foreach { column => mutation.putDelete(family, column, cellVisibility) }

      writer.addMutation(mutation)
      writer.flush
    }
  }

  override def delete(row: ByteArray, families: Seq[(String, Seq[ByteArray])]): Unit = {
    if (families.isEmpty) {
      columnFamilies.foreach { family =>
        delete(row, family)
      }
    } else {
      families.foreach { case (family, columns) =>
        delete(row, family, columns: _*)
      }
    }
  }

  override def deleteBatch(rows: Seq[ByteArray]): Unit = {
    val deleter = newBatchDeleter(numBatchThreads(rows))
    val ranges = rows.flatMap { row => Seq(Range.exact(new Text(row))) }
    deleter.setRanges(ranges)
    deleter.delete
  }

  private def rowKey(key: ByteArray): Text = if (key == null) null else new Text(key)

  private def numBatchThreads[T](rows: Seq[T]): Int = Math.min(rows.size, Runtime.getRuntime.availableProcessors)

  private def newScanner = db.connector.createScanner(name, authorizations)

  private def newBatchScanner(numQueryThreads: Int) = db.connector.createBatchScanner(name, authorizations, numQueryThreads)

  private def newBatchDeleter(numQueryThreads: Int, maxMemory: Long = 10000000L) = {
    val config = new BatchWriterConfig
      config.setMaxMemory(maxMemory)
      db.connector.createBatchDeleter(name, authorizations, numQueryThreads, config)
  }

  /** Creates a batch writer.
    *  @param maxMemory the maximum memory in bytes to batch before writing.
    *                   The smaller this value, the more frequently the BatchWriter will write.
    */
  private def newBatchWriter(maxMemory: Long = 10000000L) = {
    // Use the default durability that is the table's durability setting.
    val config = new BatchWriterConfig
    config.setMaxMemory(maxMemory)
    db.connector.createBatchWriter(name, config)
  }
}

class AccumuloRowScanner(scanner: ScannerBase) extends RowScanner {
  private val iterator = scanner.iterator
  private var cell = if (iterator.hasNext) iterator.next else null

  override def close: Unit = scanner.close

  override def hasNext: Boolean = cell != null

  def nextColumnFamily: ColumnFamily = {
    if (cell == null) throw new NoSuchElementException
    val family = cell.getKey.getColumnFamily
    val columns = new collection.mutable.ArrayBuffer[Column]
    do {
      val column = Column(cell.getKey.getColumnQualifier.copyBytes, cell.getValue.get, cell.getKey.getTimestamp)
      columns.append(column)
      if (iterator.hasNext) cell = iterator.next else cell = null
    } while (cell != null && cell.getKey.getColumnFamily.equals(family))
    ColumnFamily(family.toString, columns)
  }

  override def next: Row = {
    if (cell == null) throw new NoSuchElementException
    val rowKey = cell.getKey.getRow
    val families = new collection.mutable.ArrayBuffer[ColumnFamily]
    do {
      val family = nextColumnFamily
      families.append(family)
    } while (cell != null && cell.getKey.getRow.equals(rowKey))
    Row(rowKey.copyBytes, families)
  }
}
