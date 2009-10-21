 /**
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

package org.apache.cassandra.dht;

 import java.io.IOException;
 import java.util.ArrayList;
 import java.util.Arrays;
 import java.util.HashMap;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;

 import org.apache.log4j.Logger;

 import java.net.InetAddress;
 import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageService;


class LeaveJoinProtocolHelper
{
    private static Logger logger_ = Logger.getLogger(LeaveJoinProtocolHelper.class);
    
    /**
     * Give a range a-------------b which is being split as
     * a-----x-----y-----b then we want a mapping from 
     * (a, b] --> (a, x], (x, y], (y, b] 
    */
    protected static Map<Range, List<Range>> getRangeSplitRangeMapping(Range[] oldRanges, Token[] allTokens)
    {
        Map<Range, List<Range>> splitRanges = new HashMap<Range, List<Range>>();
        Token[] tokens = new Token[allTokens.length];
        System.arraycopy(allTokens, 0, tokens, 0, tokens.length);
        Arrays.sort(tokens);
        
        Range prevRange = null;
        Token prevToken = null;
        boolean bVal = false;
        
        for ( Range oldRange : oldRanges )
        {
            if (bVal)
            {
                bVal = false; 
                List<Range> subRanges = splitRanges.get(prevRange);
                if ( subRanges != null )
                    subRanges.add( new Range(prevToken, prevRange.right()) );     
            }
            
            prevRange = oldRange;
            prevToken = oldRange.left();                
            for (Token token : tokens)
            {     
                List<Range> subRanges = splitRanges.get(oldRange);
                if ( oldRange.contains(token) )
                {                        
                    if ( subRanges == null )
                    {
                        subRanges = new ArrayList<Range>();
                        splitRanges.put(oldRange, subRanges);
                    }                            
                    subRanges.add( new Range(prevToken, token) );
                    prevToken = token;
                    bVal = true;
                }
                else
                {
                    if ( bVal )
                    {
                        bVal = false;                                                                                
                        subRanges.add( new Range(prevToken, oldRange.right()) );                            
                    }
                }
            }
        }
        /* This is to handle the last range being processed. */
        if ( bVal )
        {
            bVal = false; 
            List<Range> subRanges = splitRanges.get(prevRange);
            subRanges.add( new Range(prevToken, prevRange.right()) );                            
        }
        return splitRanges;
    }
    
    protected static Map<Range, List<BootstrapSourceTarget>> getRangeSourceTargetInfo(Map<Range, List<InetAddress>> oldRangeToEndPointMap, Map<Range, List<InetAddress>> newRangeToEndPointMap)
    {
        Map<Range, List<BootstrapSourceTarget>> rangesWithSourceTarget = new HashMap<Range, List<BootstrapSourceTarget>>();
        /*
         * Basically calculate for each range the endpoints handling the
         * range in the old token set and in the new token set. Whoever
         * gets bumped out of the top N will have to hand off that range
         * to the new dude.
        */
        Set<Range> oldRangeSet = oldRangeToEndPointMap.keySet();
        for(Range range : oldRangeSet)
        {
            if (logger_.isDebugEnabled())
              logger_.debug("Attempting to figure out the dudes who are bumped out for " + range + " ...");
            List<InetAddress> oldEndPoints = oldRangeToEndPointMap.get(range);
            List<InetAddress> newEndPoints = newRangeToEndPointMap.get(range);
            if ( newEndPoints != null )
            {                        
                List<InetAddress> newEndPoints2 = new ArrayList<InetAddress>(newEndPoints);
                for ( InetAddress newEndPoint : newEndPoints2 )
                {
                    if ( oldEndPoints.contains(newEndPoint) )
                    {
                        oldEndPoints.remove(newEndPoint);
                        newEndPoints.remove(newEndPoint);
                    }
                }                        
            }
            else
            {
                logger_.warn("Trespassing - scram");
            }
            if (logger_.isDebugEnabled())
              logger_.debug("Done figuring out the dudes who are bumped out for range " + range + " ...");
        }
        for ( Range range : oldRangeSet )
        {                    
            List<InetAddress> oldEndPoints = oldRangeToEndPointMap.get(range);
            List<InetAddress> newEndPoints = newRangeToEndPointMap.get(range);
            List<BootstrapSourceTarget> srcTarget = rangesWithSourceTarget.get(range);
            if ( srcTarget == null )
            {
                srcTarget = new ArrayList<BootstrapSourceTarget>();
                rangesWithSourceTarget.put(range, srcTarget);
            }
            int i = 0;
            for ( InetAddress oldEndPoint : oldEndPoints )
            {                        
                srcTarget.add( new BootstrapSourceTarget(oldEndPoint, newEndPoints.get(i++)) );
            }
        }
        return rangesWithSourceTarget;
    }
    
    /**
     * This method sends messages out to nodes instructing them 
     * to stream the specified ranges to specified target nodes. 
    */
    protected static void assignWork(Map<Range, List<BootstrapSourceTarget>> rangesWithSourceTarget) throws IOException
    {
        Map<InetAddress, Map<InetAddress, List<Range>>> rangeInfo = getWorkMap(rangesWithSourceTarget);
        sendMessagesToBootstrapSources(rangeInfo);
    }
    
    /**
     * This method takes the Src -> (Tgt-> List of ranges) maps and retains those entries 
     * that are relevant to bootstrapping the target endpoint
     */
    protected static Map<InetAddress, Map<InetAddress, List<Range>>>
    filterRangesForTargetEndPoint(Map<InetAddress, Map<InetAddress, List<Range>>> rangeInfo, InetAddress targetEndPoint)
    {
        Map<InetAddress, Map<InetAddress, List<Range>>> filteredMap = new HashMap<InetAddress, Map<InetAddress,List<Range>>>();
        for (Map.Entry<InetAddress, Map<InetAddress, List<Range>>> e: rangeInfo.entrySet())
        {
            InetAddress source = e.getKey();
            Map<InetAddress, List<Range>> targets = e.getValue();
            Map<InetAddress, List<Range>> filteredTargets = new HashMap<InetAddress, List<Range>>();
            if (targets.get(targetEndPoint) != null)
                filteredTargets.put(targetEndPoint, targets.get(targetEndPoint));
            if (filteredTargets.size() > 0)
            filteredMap.put(source, filteredTargets);
        }
        return filteredMap;
    }

    private static void sendMessagesToBootstrapSources(Map<InetAddress, Map<InetAddress, List<Range>>> rangeInfo) throws IOException
    {
        Set<InetAddress> sources = rangeInfo.keySet();
        for ( InetAddress source : sources )
        {
            Map<InetAddress, List<Range>> targetRangesMap = rangeInfo.get(source);
            Set<InetAddress> targets = targetRangesMap.keySet();
            List<BootstrapMetadata> bsmdList = new ArrayList<BootstrapMetadata>();
            
            for ( InetAddress target : targets )
            {
                List<Range> rangeForTarget = targetRangesMap.get(target);
                BootstrapMetadata bsMetadata = new BootstrapMetadata(target, rangeForTarget);
                bsmdList.add(bsMetadata);
            }
            
            BootstrapMetadataMessage bsMetadataMessage = new BootstrapMetadataMessage(bsmdList.toArray( new BootstrapMetadata[0] ) );
            /* Send this message to the source to do his shit. */
            Message message = BootstrapMetadataMessage.makeBootstrapMetadataMessage(bsMetadataMessage); 
            if (logger_.isDebugEnabled())
              logger_.debug("Sending the BootstrapMetadataMessage to " + source);
            MessagingService.instance().sendOneWay(message, source);
            StorageService.instance().addBootstrapSource(source);
        }
    }

    static Map<InetAddress, Map<InetAddress, List<Range>>> getWorkMap(
            Map<Range, List<BootstrapSourceTarget>> rangesWithSourceTarget)
    {
        /*
         * Map whose key is the source node and the value is a map whose key is the
         * target and value is the list of ranges to be sent to it. 
        */
        Map<InetAddress, Map<InetAddress, List<Range>>> rangeInfo = new HashMap<InetAddress, Map<InetAddress, List<Range>>>();
        Set<Range> ranges = rangesWithSourceTarget.keySet();
        
        for ( Range range : ranges )
        {
            List<BootstrapSourceTarget> rangeSourceTargets = rangesWithSourceTarget.get(range);
            for ( BootstrapSourceTarget rangeSourceTarget : rangeSourceTargets )
            {
                Map<InetAddress, List<Range>> targetRangeMap = rangeInfo.get(rangeSourceTarget.source_);
                if ( targetRangeMap == null )
                {
                    targetRangeMap = new HashMap<InetAddress, List<Range>>();
                    rangeInfo.put(rangeSourceTarget.source_, targetRangeMap);
                }
                List<Range> rangesToGive = targetRangeMap.get(rangeSourceTarget.target_);
                if ( rangesToGive == null )
                {
                    rangesToGive = new ArrayList<Range>();
                    targetRangeMap.put(rangeSourceTarget.target_, rangesToGive);
                }
                rangesToGive.add(range);
            }
        }
        return rangeInfo;
    }
}
