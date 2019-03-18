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
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.nio.Address;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.WindowedValue;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static com.hazelcast.jet.Traversers.traverseIterable;
import static com.hazelcast.jet.beam.Utils.roundRobinSubList;
import static com.hazelcast.jet.impl.util.ExceptionUtil.rethrow;

public class BoundedSourceP<T> extends AbstractProcessor implements Traverser {

    private final Traverser<BoundedSource<T>> shardsTraverser;
    private final PipelineOptions options;
    private final String ownerId; //todo: do not remove it, very useful for debugging

    private BoundedSource.BoundedReader currentReader;

    private BoundedSourceP(List<BoundedSource<T>> shards, PipelineOptions options, String ownerId) {
        this.shardsTraverser = traverseIterable(shards);
        this.options = options;
        this.ownerId = ownerId;
    }

    @Override
    protected void init(@Nonnull Processor.Context context) throws Exception {
        nextShard();
    }

    @Override
    public Object next() {
        if (currentReader == null) {
            return null;
        }
        try {
            Object item = currentReader.getCurrent();
            //todo: this might need to get more flexible
            WindowedValue<Object> res = WindowedValue.timestampedValueInGlobalWindow(item, currentReader.getCurrentTimestamp());
            if (!currentReader.advance()) {
                nextShard();
            }
            return res;
        } catch (IOException e) {
            throw rethrow(e);
        }
    }

    /**
     * Called when currentReader is null or drained. At the end it will
     * contain a started reader of the next shard or null.
     */
    private void nextShard() throws IOException {
        for (; ; ) {
            if (currentReader != null) {
                currentReader.close();
                currentReader = null;
            }
            BoundedSource<T> shard = shardsTraverser.next();
            if (shard == null) {
                break; // all shards done
            }
            currentReader = shard.createReader(options);
            if (currentReader.start()) {
                break;
            }
        }
    }

    @Override
    public boolean complete() {
        return emitFromTraverser(this);
    }

    @Override
    public void close() throws Exception {
        if (currentReader != null) {
            currentReader.close();
        }
    }

    public static <T> ProcessorMetaSupplier supplier(
            BoundedSource<T> boundedSource,
            SerializablePipelineOptions options,
            String ownerId
    ) {
        return new BoundedSourceMetaProcessorSupplier<>(boundedSource, options, ownerId);
    }

    private static class BoundedSourceMetaProcessorSupplier<T> implements ProcessorMetaSupplier {

        private final BoundedSource<T> boundedSource;
        private final SerializablePipelineOptions options;
        private final String ownerId;

        private transient List<? extends BoundedSource<T>> shards;

        private BoundedSourceMetaProcessorSupplier(
                BoundedSource<T> boundedSource,
                SerializablePipelineOptions options,
                String ownerId
        ) {
            this.boundedSource = boundedSource;
            this.options = options;
            this.ownerId = ownerId;
        }

        @Override
        public void init(@Nonnull ProcessorMetaSupplier.Context context) throws Exception {
            long desiredSizeBytes = Math.max(1, boundedSource.getEstimatedSizeBytes(options.get()) / context.totalParallelism());
            shards = boundedSource.split(desiredSizeBytes, options.get());
        }

        @SuppressWarnings("unchecked")
        @Nonnull
        @Override
        public Function<? super Address, ? extends ProcessorSupplier> get(@Nonnull List<Address> addresses) {
            return address -> new BoundedSourceProcessorSupplier(
                    roundRobinSubList(shards, addresses.indexOf(address), addresses.size()), options, ownerId);
        }
    }

    private static class BoundedSourceProcessorSupplier<T> implements ProcessorSupplier {
        private final List<BoundedSource<T>> shards;
        private final SerializablePipelineOptions options;
        private final String ownerId;
        private transient ProcessorSupplier.Context context;

        private BoundedSourceProcessorSupplier(
                List<BoundedSource<T>> shards,
                SerializablePipelineOptions options,
                String ownerId
        ) {
            this.shards = shards;
            this.options = options;
            this.ownerId = ownerId;
        }

        @Override
        public void init(@Nonnull Context context) {
            this.context = context;
        }

        @Nonnull
        @Override
        public Collection<? extends Processor> get(int count) {
            int indexBase = context.memberIndex() * context.localParallelism();
            List<Processor> res = new ArrayList<>(count);
            for (int i = 0; i < count; i++, indexBase++) {
                res.add(new BoundedSourceP<>(roundRobinSubList(shards, i, count), options.get(), ownerId));
            }
            return res;
        }
    }
}
