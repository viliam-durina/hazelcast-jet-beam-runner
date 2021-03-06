/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.beam.processors;

import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.beam.SideInputValue;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.joda.time.Instant;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hazelcast.jet.core.ProcessorMetaSupplier.forceTotalParallelismOne;

/**
 * Collects all input {@link WindowedValue}s, groups them by windows and when
 * input is complete emits one {@link SideInputValue} for each window.
 */
public class ViewP extends AbstractProcessor {

    private final TimestampCombiner timestampCombiner;
    private final PCollectionView view;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final String ownerId; //do not remove, useful for debugging

    private Map<BoundedWindow, TimestampAndValues> values = new HashMap<>();
    private PaneInfo paneInfo = PaneInfo.NO_FIRING;
    private Traverser<SideInputValue> resultTraverser;

    private ViewP(PCollectionView view, WindowingStrategy windowingStrategy, String ownerId) {
        this.timestampCombiner = windowingStrategy.getTimestampCombiner();
        this.view = view;
        this.ownerId = ownerId;
        //System.out.println(ViewP.class.getSimpleName() + " CREATE ownerId = " + ownerId); //useful for debugging
    }

    @Override
    protected boolean tryProcess(int ordinal, @Nonnull Object item) {
        //System.out.println(ViewP.class.getSimpleName() + " UPDATE ownerId = " + ownerId + ", item = " + item); //useful for debugging
        WindowedValue<?> windowedValue = (WindowedValue<?>) item;
        for (BoundedWindow window : windowedValue.getWindows()) {
            values
                    .merge(window,
                            new TimestampAndValues(windowedValue.getTimestamp(), windowedValue.getValue()),
                            (o, n) -> o.merge(timestampCombiner, n));
        }

        if (!paneInfo.equals(windowedValue.getPane())) throw new RuntimeException("Oops!");
        return true;
    }

    @Override
    public boolean complete() {
        //System.out.println(ViewP.class.getSimpleName() + " COMPLETE ownerId = " + ownerId); //useful for debugging
        if (resultTraverser == null) {

            Collection windowedValues = values.entrySet().stream()
                    .map(
                            e -> {
                                BoundedWindow window = e.getKey();
                                TimestampAndValues value = e.getValue();
                                return WindowedValue.of(value.getValues(), value.timestamp, Collections.singleton(window), paneInfo);
                            }
                    )
                    .collect(Collectors.toList());
            resultTraverser = Traversers.singleton(new SideInputValue(view, windowedValues));
        }
        return emitFromTraverser(resultTraverser);
    }

    public static ProcessorMetaSupplier supplier(PCollectionView<?> view, WindowingStrategy<?, ?> windowingStrategy, String ownerId) {
        return forceTotalParallelismOne(ProcessorSupplier.of(() -> new ViewP(view, windowingStrategy, ownerId)));
    }

    public static class TimestampAndValues {
        private final List<Object> values = new ArrayList<>();
        private Instant timestamp;

        TimestampAndValues(Instant timestamp, Object value) {
            this.timestamp = timestamp;
            values.add(value);
        }

        public Iterable<Object> getValues() {
            return values;
        }

        TimestampAndValues merge(TimestampCombiner timestampCombiner, TimestampAndValues v2) {
            timestamp = timestampCombiner.combine(timestamp, v2.timestamp);
            values.addAll(v2.values);
            return this;
        }
    }
}
