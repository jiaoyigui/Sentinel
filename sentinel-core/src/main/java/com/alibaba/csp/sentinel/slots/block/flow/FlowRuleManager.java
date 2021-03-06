package com.alibaba.csp.sentinel.slots.block.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.metric.MetricTimerListener;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.PropertyListener;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.controller.DefaultController;
import com.alibaba.csp.sentinel.slots.block.flow.controller.PaceController;
import com.alibaba.csp.sentinel.slots.block.flow.controller.WarmUpController;

/**
 * <p>
 * One resources can have multiple rules. And these rules take effects in the
 * following order:
 * <ol>
 * <li>requests from specified caller</li>
 * <li>no specified caller</li>
 * </ol>
 * </p>
 *
 * @author jialiang.linjl
 */

public class FlowRuleManager {

    private static final Map<String, List<FlowRule>> flowRules = new ConcurrentHashMap<String, List<FlowRule>>();
    private final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final static FlowPropertyListener listener = new FlowPropertyListener();
    private static SentinelProperty<List<FlowRule>> currentProperty = new DynamicSentinelProperty<List<FlowRule>>();

    static {
        currentProperty.addListener(listener);
        scheduler.scheduleAtFixedRate(new MetricTimerListener(), 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Listen to the {@link SentinelProperty} for {@link FlowRule}s. The property is the source of {@link FlowRule}s.
     * Flow rules can also be set by {@link #loadRules(List)} directly.
     *
     * @param property the property to listen.
     */
    public static void register2Property(SentinelProperty<List<FlowRule>> property) {
        synchronized (listener) {
            currentProperty.removeListener(listener);
            property.addListener(listener);
            currentProperty = property;
        }
    }

    /**
     * Get a copy of the rules.
     *
     * @return a new copy of the rules.
     */
    public static List<FlowRule> getRules() {
        List<FlowRule> rules = new ArrayList<FlowRule>();
        if (flowRules == null) {
            return rules;
        }
        for (Map.Entry<String, List<FlowRule>> entry : flowRules.entrySet()) {
            rules.addAll(entry.getValue());
        }
        return rules;
    }

    /**
     * Load {@link FlowRule}s, former rules will be replaced.
     *
     * @param rules new rules to load.
     */
    public static void loadRules(List<FlowRule> rules) {
        currentProperty.updateValue(rules);
    }

    private static Map<String, List<FlowRule>> loadFlowConf(List<FlowRule> list) {
        Map<String, List<FlowRule>> newRuleMap = new ConcurrentHashMap<String, List<FlowRule>>();

        if (list == null) {
            return newRuleMap;
        }

        for (FlowRule rule : list) {
            if (StringUtil.isBlank(rule.getLimitApp())) {
                rule.setLimitApp(FlowRule.DEFAULT);
            }

            Controller rater = new DefaultController(rule.getCount(), rule.getGrade());
            if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS
                && rule.getControlBehavior() == RuleConstant.CONTROL_BEHAVIOR_WARM_UP
                && rule.getWarmUpPeriodSec() > 0) {
                rater = new WarmUpController(rule.getCount(), rule.getWarmUpPeriodSec(), ColdFactorProperty.coldFactor);

            } else if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS
                && rule.getControlBehavior() == RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER
                && rule.getMaxQueueingTimeMs() > 0) {
                rater = new PaceController(rule.getMaxQueueingTimeMs(), rule.getCount());
            }
            rule.setRater(rater);

            String identity = rule.getResource();
            List<FlowRule> ruleM = newRuleMap.get(identity);

            if (ruleM == null) {
                ruleM = new ArrayList<FlowRule>();
                newRuleMap.put(identity, ruleM);
            }

            ruleM.add(rule);

        }
        return newRuleMap;
    }

    public static void checkFlow(ResourceWrapper resource, Context context, DefaultNode node, int count)
        throws BlockException {
        if (flowRules != null) {
            List<FlowRule> rules = flowRules.get(resource.getName());
            if (rules != null) {
                for (FlowRule rule : rules) {
                    if (!rule.passCheck(context, node, count)) {
                        throw new FlowException(rule.getLimitApp());
                    }
                }
            }
        }
    }

    public static boolean hasConfig(String resource) {
        return flowRules.containsKey(resource);
    }

    public static boolean isOtherOrigin(String origin, String resourceName) {
        if (StringUtil.isEmpty(origin)) {
            return false;
        }

        if (flowRules != null) {
            List<FlowRule> rules = flowRules.get(resourceName);

            if (rules != null) {
                for (FlowRule rule : rules) {
                    if (origin.equals(rule.getLimitApp())) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static final class FlowPropertyListener implements PropertyListener<List<FlowRule>> {

        @Override
        public void configUpdate(List<FlowRule> value) {
            Map<String, List<FlowRule>> rules = loadFlowConf(value);
            if (rules != null) {
                flowRules.clear();
                flowRules.putAll(rules);
            }
            RecordLog.info("receive flow config: " + flowRules);
        }

        @Override
        public void configLoad(List<FlowRule> conf) {
            Map<String, List<FlowRule>> rules = loadFlowConf(conf);
            if (rules != null) {
                flowRules.clear();
                flowRules.putAll(rules);
            }
            RecordLog.info("load flow config: " + flowRules);
        }

    }

}
