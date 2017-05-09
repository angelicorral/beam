/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink.streaming;

import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertThat;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import org.apache.beam.runners.flink.translation.wrappers.streaming.io.DedupingOperator;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.ValueWithRecordId;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.OperatorStateHandles;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link DedupingOperator}.
 */
@RunWith(JUnit4.class)
public class DedupingOperatorTest {

  @Test
  public void testDeduping() throws Exception {

    KeyedOneInputStreamOperatorTestHarness<
        ByteBuffer,
        WindowedValue<ValueWithRecordId<String>>,
        WindowedValue<String>> harness = getDebupingHarness();

    harness.open();

    String key1 = "key1";
    String key2 = "key2";

    harness.processElement(new StreamRecord<>(
        WindowedValue.valueInGlobalWindow(new ValueWithRecordId<>(key1, key1.getBytes()))));

    harness.processElement(new StreamRecord<>(
        WindowedValue.valueInGlobalWindow(new ValueWithRecordId<>(key2, key2.getBytes()))));

    harness.processElement(new StreamRecord<>(
        WindowedValue.valueInGlobalWindow(new ValueWithRecordId<>(key1, key1.getBytes()))));

    assertThat(
        this.<String>stripStreamRecordFromWindowedValue(harness.getOutput()),
        contains(WindowedValue.valueInGlobalWindow(key1),
            WindowedValue.valueInGlobalWindow(key2)));

    OperatorStateHandles snapshot = harness.snapshot(0L, 0L);

    harness.close();

    harness = getDebupingHarness();
    harness.setup();
    harness.initializeState(snapshot);
    harness.open();

    String key3 = "key3";

    harness.processElement(new StreamRecord<>(
        WindowedValue.valueInGlobalWindow(new ValueWithRecordId<>(key2, key2.getBytes()))));

    harness.processElement(new StreamRecord<>(
        WindowedValue.valueInGlobalWindow(new ValueWithRecordId<>(key3, key3.getBytes()))));

    assertThat(
        this.<String>stripStreamRecordFromWindowedValue(harness.getOutput()),
        contains(WindowedValue.valueInGlobalWindow(key3)));

    harness.close();
  }

  private KeyedOneInputStreamOperatorTestHarness<ByteBuffer,
      WindowedValue<ValueWithRecordId<String>>,
      WindowedValue<String>> getDebupingHarness() throws Exception {
    DedupingOperator<String> operator = new DedupingOperator<>();

    return new KeyedOneInputStreamOperatorTestHarness<>(operator,
        new KeySelector<WindowedValue<ValueWithRecordId<String>>, ByteBuffer>() {
      @Override
      public ByteBuffer getKey(WindowedValue<ValueWithRecordId<String>> value) throws Exception {
        return ByteBuffer.wrap(value.getValue().getId());
      }
    }, TypeInformation.of(ByteBuffer.class));
  }

  private <T> Iterable<WindowedValue<T>> stripStreamRecordFromWindowedValue(
      Iterable<Object> input) {

    return FluentIterable.from(input).filter(new Predicate<Object>() {
      @Override
      public boolean apply(@Nullable Object o) {
        return o instanceof StreamRecord && ((StreamRecord) o).getValue() instanceof WindowedValue;
      }
    }).transform(new Function<Object, WindowedValue<T>>() {
      @Nullable
      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public WindowedValue<T> apply(@Nullable Object o) {
        if (o instanceof StreamRecord && ((StreamRecord) o).getValue() instanceof WindowedValue) {
          return (WindowedValue) ((StreamRecord) o).getValue();
        }
        throw new RuntimeException("unreachable");
      }
    });
  }
}