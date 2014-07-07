package org.apache.cassandra.stress.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;

import org.apache.cassandra.stress.Operation;
import org.apache.cassandra.stress.generate.DistributionFactory;
import org.apache.cassandra.stress.generate.PartitionGenerator;
import org.apache.cassandra.stress.util.Timer;

public abstract class SampledOpDistributionFactory<T> implements OpDistributionFactory
{

    final List<Pair<T, Double>> ratios;
    final DistributionFactory clustering;
    protected SampledOpDistributionFactory(List<Pair<T, Double>> ratios, DistributionFactory clustering)
    {
        this.ratios = ratios;
        this.clustering = clustering;
    }

    protected abstract Operation get(Timer timer, PartitionGenerator generator, T key);
    protected abstract PartitionGenerator newGenerator();

    public OpDistribution get(Timer timer)
    {
        PartitionGenerator generator = newGenerator();
        List<Pair<Operation, Double>> operations = new ArrayList<>();
        for (Pair<T, Double> ratio : ratios)
            operations.add(new Pair<>(get(timer, generator, ratio.getFirst()), ratio.getSecond()));
        return new SampledOpDistribution(new EnumeratedDistribution<>(operations), clustering.get());
    }

    public String desc()
    {
        List<T> keys = new ArrayList<>();
        for (Pair<T, Double> p : ratios)
            keys.add(p.getFirst());
        return keys.toString();
    }

    public Iterable<OpDistributionFactory> each()
    {
        List<OpDistributionFactory> out = new ArrayList<>();
        for (final Pair<T, Double> ratio : ratios)
        {
            out.add(new OpDistributionFactory()
            {
                public OpDistribution get(Timer timer)
                {
                    return new FixedOpDistribution(SampledOpDistributionFactory.this.get(timer, newGenerator(), ratio.getFirst()));
                }

                public String desc()
                {
                    return ratio.getFirst().toString();
                }

                public Iterable<OpDistributionFactory> each()
                {
                    return Collections.<OpDistributionFactory>singleton(this);
                }
            });
        }
        return out;
    }

}
