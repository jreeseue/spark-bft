/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.columnar

import java.nio.ByteBuffer

import org.scalatest.FunSuite

import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.columnar.ColumnarTestUtils._
import org.apache.spark.sql.execution.SparkSqlSerializer

class ColumnTypeSuite extends FunSuite {
  val DEFAULT_BUFFER_SIZE = 512

  test("defaultSize") {
    val checks = Map(
      INT -> 4, SHORT -> 2, LONG -> 8, BYTE -> 1, DOUBLE -> 8, FLOAT -> 4,
      BOOLEAN -> 1, STRING -> 8, BINARY -> 16, GENERIC -> 16)

    checks.foreach { case (columnType, expectedSize) =>
      expectResult(expectedSize, s"Wrong defaultSize for $columnType") {
        columnType.defaultSize
      }
    }
  }

  test("actualSize") {
    def checkActualSize[T <: DataType, JvmType](
        columnType: ColumnType[T, JvmType],
        value: JvmType,
        expected: Int) {

      expectResult(expected, s"Wrong actualSize for $columnType") {
        columnType.actualSize(value)
      }
    }

    checkActualSize(INT,     Int.MaxValue,    4)
    checkActualSize(SHORT,   Short.MaxValue,  2)
    checkActualSize(LONG,    Long.MaxValue,   8)
    checkActualSize(BYTE,    Byte.MaxValue,   1)
    checkActualSize(DOUBLE,  Double.MaxValue, 8)
    checkActualSize(FLOAT,   Float.MaxValue,  4)
    checkActualSize(BOOLEAN, true,            1)
    checkActualSize(STRING,  "hello",         4 + 5)

    val binary = Array.fill[Byte](4)(0: Byte)
    checkActualSize(BINARY,  binary, 4 + 4)

    val generic = Map(1 -> "a")
    checkActualSize(GENERIC, SparkSqlSerializer.serialize(generic), 4 + 11)
  }

  testNativeColumnType[BooleanType.type](
    BOOLEAN,
    (buffer: ByteBuffer, v: Boolean) => {
      buffer.put((if (v) 1 else 0).toByte)
    },
    (buffer: ByteBuffer) => {
      buffer.get() == 1
    })

  testNativeColumnType[IntegerType.type](INT, _.putInt(_), _.getInt)

  testNativeColumnType[ShortType.type](SHORT, _.putShort(_), _.getShort)

  testNativeColumnType[LongType.type](LONG, _.putLong(_), _.getLong)

  testNativeColumnType[ByteType.type](BYTE, _.put(_), _.get)

  testNativeColumnType[DoubleType.type](DOUBLE, _.putDouble(_), _.getDouble)

  testNativeColumnType[FloatType.type](FLOAT, _.putFloat(_), _.getFloat)

  testNativeColumnType[StringType.type](
    STRING,
    (buffer: ByteBuffer, string: String) => {
      val bytes = string.getBytes()
      buffer.putInt(bytes.length).put(string.getBytes)
    },
    (buffer: ByteBuffer) => {
      val length = buffer.getInt()
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      new String(bytes)
    })

  testColumnType[BinaryType.type, Array[Byte]](
    BINARY,
    (buffer: ByteBuffer, bytes: Array[Byte]) => {
      buffer.putInt(bytes.length).put(bytes)
    },
    (buffer: ByteBuffer) => {
      val length = buffer.getInt()
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      bytes
    })

  test("GENERIC") {
    val buffer = ByteBuffer.allocate(512)
    val obj = Map(1 -> "spark", 2 -> "sql")
    val serializedObj = SparkSqlSerializer.serialize(obj)

    GENERIC.append(SparkSqlSerializer.serialize(obj), buffer)
    buffer.rewind()

    val length = buffer.getInt()
    assert(length === serializedObj.length)

    expectResult(obj, "Deserialized object didn't equal to the original object") {
      val bytes = new Array[Byte](length)
      buffer.get(bytes, 0, length)
      SparkSqlSerializer.deserialize(bytes)
    }

    buffer.rewind()
    buffer.putInt(serializedObj.length).put(serializedObj)

    expectResult(obj, "Deserialized object didn't equal to the original object") {
      buffer.rewind()
      SparkSqlSerializer.deserialize(GENERIC.extract(buffer))
    }
  }

  def testNativeColumnType[T <: NativeType](
      columnType: NativeColumnType[T],
      putter: (ByteBuffer, T#JvmType) => Unit,
      getter: (ByteBuffer) => T#JvmType) {

    testColumnType[T, T#JvmType](columnType, putter, getter)
  }

  def testColumnType[T <: DataType, JvmType](
      columnType: ColumnType[T, JvmType],
      putter: (ByteBuffer, JvmType) => Unit,
      getter: (ByteBuffer) => JvmType) {

    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
    val seq = (0 until 4).map(_ => makeRandomValue(columnType))

    test(s"$columnType.extract") {
      buffer.rewind()
      seq.foreach(putter(buffer, _))

      buffer.rewind()
      seq.foreach { expected =>
        assert(
          expected === columnType.extract(buffer),
          "Extracted value didn't equal to the original one")
      }
    }

    test(s"$columnType.append") {
      buffer.rewind()
      seq.foreach(columnType.append(_, buffer))

      buffer.rewind()
      seq.foreach { expected =>
        assert(
          expected === getter(buffer),
          "Extracted value didn't equal to the original one")
      }
    }
  }
}
