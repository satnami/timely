package timely.subscription;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import timely.adapter.accumulo.MetricAdapter;
import timely.api.response.MetricResponse;
import timely.api.response.TimelyException;
import timely.model.Metric;
import timely.store.DataStore;
import timely.util.JsonUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MetricScanner extends Thread implements UncaughtExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MetricScanner.class);
    private final Scanner scanner;
    private Iterator<Entry<Key, Value>> iter = null;
    private final ChannelHandlerContext ctx;
    private volatile boolean closed = false;
    private final long delay;
    private final String name;
    private final int lag;
    private final String subscriptionId;
    private final String metric;
    private final long endTime;
    private final Subscription subscription;

    public MetricScanner(Subscription sub, String subscriptionId, String sessionId, DataStore store, String metric,
            Map<String, String> tags, long startTime, long endTime, long delay, int lag, ChannelHandlerContext ctx)
            throws TimelyException {
        this.setDaemon(true);
        this.setUncaughtExceptionHandler(this);
        this.subscription = sub;
        this.ctx = ctx;
        this.lag = lag;
        this.metric = metric;
        this.endTime = endTime;
        this.scanner = store.createScannerForMetric(sessionId, metric, tags, startTime, endTime, lag);
        this.iter = scanner.iterator();
        this.delay = delay;
        this.subscriptionId = subscriptionId;
        ToStringBuilder buf = new ToStringBuilder(this);
        buf.append("sessionId", sessionId);
        buf.append("metric", metric);
        buf.append("startTime", startTime);
        buf.append("endTime", endTime);
        buf.append("delayTime", delay);
        if (null != tags) {
            buf.append("tags", tags.toString());
        }
        name = buf.toString();
        LOG.trace("Created MetricScanner: {}", name);
        this.setName("Metric Scanner " + name);
    }

    @Override
    public void run() {
        Metric m = null;
        ObjectMapper om = JsonUtil.getObjectMapper();
        try {
            while (!closed) {

                if (this.iter.hasNext()) {
                    Entry<Key, Value> e = this.iter.next();
                    m = MetricAdapter.parse(e.getKey(), e.getValue(), true);
                    try {
                        String json = om.writeValueAsString(MetricResponse.fromMetric(m, this.subscriptionId));
                        LOG.trace("Returning {} for subscription", json);
                        this.ctx.writeAndFlush(new TextWebSocketFrame(json));
                    } catch (JsonProcessingException e1) {
                        LOG.error("Error serializing metric: " + m, e1);
                    }
                } else if (this.endTime == 0) {
                    long endTimeStamp = (System.currentTimeMillis() - (lag * 1000));
                    byte[] end = MetricAdapter.encodeRowKey(this.metric, endTimeStamp);
                    Text endRow = new Text(end);
                    this.scanner.close();
                    Range prevRange = this.scanner.getRange();
                    if (null == m) {
                        LOG.debug("No results found, waiting {}ms to retry with new end time {}.", delay, endTimeStamp);
                        sleepUninterruptibly(delay, TimeUnit.MILLISECONDS);
                        this.scanner.setRange(new Range(prevRange.getStartKey().getRow(), prevRange
                                .isStartKeyInclusive(), endRow, false));
                        this.iter = this.scanner.iterator();
                    } else {
                        // Reset the starting range to the last key returned
                        LOG.debug("Exhausted scanner, waiting {}ms to retry with new end time {}.", delay, endTimeStamp);
                        sleepUninterruptibly(delay, TimeUnit.MILLISECONDS);
                        this.scanner.setRange(new Range(new Text(MetricAdapter.encodeRowKey(m)), false, endRow,
                                prevRange.isEndKeyInclusive()));
                        this.iter = this.scanner.iterator();
                    }
                } else {
                    LOG.debug("Exhausted scanner, sending completed message for subscription {}", this.subscriptionId);
                    try {
                        MetricResponse last = new MetricResponse();
                        last.setSubscriptionId(this.subscriptionId);
                        last.setComplete(true);
                        this.ctx.writeAndFlush(new TextWebSocketFrame(om.writeValueAsString(last)));
                    } catch (JsonProcessingException e1) {
                        LOG.error("Error serializing metric: " + m, e1);
                    }
                    break;
                }
            }
        } finally {
            close();
            this.scanner.close();
            subscription.scannerComplete(metric);
        }
    }

    public void close() {
        LOG.info("Marking metric scanner closed: {}", name);
        this.closed = true;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        LOG.error("Error during metric scanner " + name, e);
        this.close();
    }
}
