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

package com.hazelcast.jet.beam;

import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.metrics.DistributionResult;
import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.joda.time.Duration;

import javax.annotation.Nullable;
import java.util.Collections;

public class FailedRunningPipelineResults implements PipelineResult {

    private final RuntimeException cause;

    public FailedRunningPipelineResults(RuntimeException cause) {
        this.cause = cause;
    }

    public RuntimeException getCause() {
        return cause;
    }

    @Override
    public State getState() {
        return State.DONE;
    }

    @Override
    public State cancel() {
        return State.DONE;
    }

    @Override
    public State waitUntilFinish(Duration duration) {
        return State.DONE;
    }

    @Override
    public State waitUntilFinish() {
        return State.DONE;
    }

    @Override
    public MetricResults metrics() {
        return new MetricResults() {
            @Override
            public MetricQueryResults queryMetrics(@Nullable MetricsFilter filter) {
                return new MetricQueryResults() {
                    @Override
                    public Iterable<MetricResult<Long>> getCounters() {
                        return Collections.emptyList();
                    }

                    @Override
                    public Iterable<MetricResult<DistributionResult>> getDistributions() {
                        return Collections.emptyList();
                    }

                    @Override
                    public Iterable<MetricResult<GaugeResult>> getGauges() {
                        return Collections.emptyList();
                    }
                };
            }
        };
    }
}
