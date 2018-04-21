/*
 * Copyright (C) 2018 Edward Raff
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jsat.linear.vectorcollection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import jsat.linear.Vec;
import jsat.linear.distancemetrics.DistanceMetric;
import jsat.utils.BoundedSortedList;
import jsat.utils.DoubleList;
import jsat.utils.IndexTable;
import jsat.utils.IntList;
import static java.lang.Math.*;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import jsat.utils.ListUtils;
/**
 *
 * @author Edward Raff
 * @param <V>
 */
public interface DualTree<V extends Vec> extends VectorCollection<V>
{
    
    public IndexNode getRoot();

    @Override
    public DualTree<V> clone();
    
    default public double dist(int self_index, int other_index, DualTree<V> other)
    {
        
        return getDistanceMetric().dist(this.get(self_index), other.get(self_index));
    }

    @Override
    public void search(Vec query, int numNeighbors, List<Integer> neighbors, List<Double> distances);
    
    
    default public void search(DualTree<V> Q, int numNeighbors, List<List<Integer>> neighbors, List<List<Double>> distances )
    {
        
        //Mpa each node to a cached value. This is used for recursive bound updates
        IdentityHashMap<IndexNode, Double> query_B_cache = new IdentityHashMap<>(Q.size());
        
        //For each item in Q, we want to find its nearest neighbor in THIS collection. 
        //each item in Q gets a priority queue of k-nns
        List<BoundedSortedList<IndexDistPair>> allPriorities = new ArrayList<>();
        for(int i = 0; i < Q.size(); i++)
            allPriorities.add(new BoundedSortedList<>(numNeighbors));
        
        ///For simplicity and fast calculations, lets combine acceleration caches into one view
        final List<Double> this_cache = this.getAccelerationCache();
        final List<Double> other_cache = Q.getAccelerationCache();
        
        final int N_r = this.size();
        final List<Double> wholeCache = ListUtils.mergedView(this_cache, other_cache);
        final List<Vec> allVecs = new ArrayList<>(N_r+Q.size());
        for(int i = 0; i < N_r; i++)
            allVecs.add(this.get(i));
        for(int i = 0; i < Q.size(); i++)
            allVecs.add(Q.get(i));
        
        DistanceMetric dm = getDistanceMetric();
        
        BaseCaseDT base = (int r_indx, int q_indx) ->
        {
            double d = dm.dist(r_indx, N_r+q_indx, allVecs, wholeCache);
            
            allPriorities.get(q_indx).add(new IndexDistPair(r_indx, d));
            return d;
        };
        
        
        
        ScoreDT score = (IndexNode ref, IndexNode query) ->
        {
            
            double bound_1 = Double.NEGATIVE_INFINITY;
            for(int p = 0; p < query.numPoints(); p++)
            {
                BoundedSortedList<IndexDistPair> D_p = allPriorities.get(query.getPoint(p));
                if(D_p.size() == numNeighbors)//has enough neighbors to return a meaningful boun
                    bound_1 = max(bound_1, D_p.last().dist);
                else//can't bound
                {
                    bound_1 = Double.POSITIVE_INFINITY;
                    break;
                }   
            }
            
            if(Double.isInfinite(bound_1))
                bound_1 = Double.POSITIVE_INFINITY;
            for(int c = 0; c < query.numChildren(); c++)
            {
                double B_nc = query_B_cache.getOrDefault(query.getChild(c), Double.POSITIVE_INFINITY);
                bound_1 = max(bound_1, B_nc);
            }
            
            
            ///compute bound 2i. First set to infinity, and find min portion
            double bound_2i = Double.POSITIVE_INFINITY;
            for(int i = 0; i < query.numPoints(); i++)
            {
                int qi_indx = query.getPoint(i);
                if(allPriorities.get(qi_indx).size() >= numNeighbors)
                    bound_2i = min(bound_2i, allPriorities.get(qi_indx).last().dist);
            }
            //then add the remaining 2 terms, which are constant for a given Node Q. If no valid points, bound remains infinite
            bound_2i += query.furthestPointDistance() +  query.furthestDescendantDistance();
            
            //Compute 3rd bound 
            double lambda_q = query.furthestDescendantDistance();
            double bound_3 = Double.POSITIVE_INFINITY;
            for(int c = 0; c < query.numChildren(); c++)
            {
                IndexNode n_c = query.getChild(c);
                bound_3 = min(bound_3, query_B_cache.getOrDefault(n_c, Double.POSITIVE_INFINITY) + 2*(lambda_q-n_c.furthestDescendantDistance()));
            }
            double bound_4 = query_B_cache.getOrDefault(query.getParrent(), Double.POSITIVE_INFINITY);
            
            final double bound_final = min(min(bound_1, bound_2i), min(bound_3, bound_4));
//            final double bound_final = bound_4;
            final double d_min_b = ref.minNodeDistance(query);
            if(Double.isFinite(bound_final))
            {
                query_B_cache.put(query, bound_final);
                
//                System.out.println(d_min_b + " " + bound_final);
                if(d_min_b > bound_final)//YAY we can prune!
                    return Double.NaN;
            }
            //default case, don't prune
            return d_min_b;
        };
        
//        IndexNode.dual_depth_first(this.getRoot(), Q.getRoot(), base, score, true);
        traverse(Q, base, score, true);
        
        
        neighbors.clear();
        distances.clear();
        for(int i = 0; i < Q.size(); i++)
        {
            IntList n = new IntList(numNeighbors);
            DoubleList d = new DoubleList(numNeighbors);
            
            BoundedSortedList<IndexDistPair> knn = allPriorities.get(i);
            for(int j = 0; j < knn.size(); j++)
            {
                IndexDistPair ip = knn.get(j);
                n.add(ip.getIndex());
                d.add(ip.getDist());
            }
            neighbors.add(n);
            distances.add(d);
            
        }
        
    }
    
    default public void search(DualTree<V> Q, double r_min, double r_max, List<List<Integer>> neighbors, List<List<Double>> distances )
    {
        neighbors.clear();
        distances.clear();
        for(int i = 0; i < Q.size(); i++)
        {
            neighbors.add(new ArrayList<>());
            distances.add(new ArrayList<>());
        }
        
        ///For simplicity and fast calculations, lets combine acceleration caches into one view
        final List<Double> this_cache = this.getAccelerationCache();
        final List<Double> other_cache = Q.getAccelerationCache();
        
        final int N_r = this.size();
        final List<Double> wholeCache = ListUtils.mergedView(this_cache, other_cache);
        final List<Vec> allVecs = new ArrayList<>(N_r+Q.size());
        for(int i = 0; i < N_r; i++)
            allVecs.add(this.get(i));
        for(int i = 0; i < Q.size(); i++)
            allVecs.add(Q.get(i));
        
        DistanceMetric dm = getDistanceMetric();
        
        BaseCaseDT base = (int r_indx, int q_indx) ->
        {
            double d = dm.dist(r_indx, N_r+q_indx, allVecs, wholeCache);
            if(r_min <= d && d <= r_max)
            {
                neighbors.get(q_indx).add(r_indx);
                distances.get(q_indx).add(d);
            }
            return d;
        };
        
        ScoreDT score = (IndexNode ref, IndexNode query) ->
        {
            double d_min = ref.minNodeDistance(query);
            double d_max = ref.maxNodeDistance(query);
            if(d_min > r_max || d_max < r_min)//If min dist is greater than max-range, or max distance is greater than min-range, we can prune
                return Double.NaN;
            
            return d_min;
        };
        
        traverse(Q, base, score, false);
        
        //Now lets sort the returned lists
        for(int i = 0; i < neighbors.size(); i++)
        {
            IndexTable it = new IndexTable(distances.get(i));
            it.apply(distances.get(i));
            it.apply(neighbors.get(i));
        }
    }

    default void traverse(DualTree<V> Q, BaseCaseDT base, ScoreDT score, boolean improvedTraverse)
    {
        //Range search dosn't benefit from improved search order. So use basic one and avoid extra overhead
        if(this.getRoot().allPointsInLeaves())
            IndexNode.dual_depth_first(this.getRoot(), Q.getRoot(), base, score, improvedTraverse);
        else//we need more index structure
        {
            IdentityHashMap<IndexNode, Set<IndexNode>> alreadySeen = new IdentityHashMap<>();
            //Pre-populate keys for thread safe traversal 
            Stack<IndexNode> toAdd = new Stack<>();
            toAdd.add(this.getRoot());
            while(!toAdd.isEmpty())
            {
                IndexNode node = toAdd.pop();
                alreadySeen.put(node, new HashSet<>());
                for(int i = 0; i < node.numChildren(); i++)
                    toAdd.add(node.getChild(i));
            }
            
            IndexNode.dual_depth_first_pointsInBranches(this.getRoot(), Q.getRoot(), base, score, improvedTraverse, alreadySeen);
        }
    }
}
