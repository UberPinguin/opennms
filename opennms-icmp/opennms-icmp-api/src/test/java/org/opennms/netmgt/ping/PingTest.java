package org.opennms.netmgt.ping;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Map;

import org.opennms.core.utils.CollectionMath;
import org.opennms.netmgt.ping.Pinger;

import junit.framework.TestCase;

public class PingTest extends TestCase {
    private Pinger m_pinger = null;
    private InetAddress m_goodHost = null;
    private InetAddress m_badHost = null;

    /**
     * Don't run this test unless the <classNameWithPackage>.runTest property
     * is set to "true".
     */
    @Override
    protected void runTest() throws Throwable {
        if (!isRunTest()) {
            System.err.println("Skipping test '" + getName() + "' because system property '" + getRunTestProperty() + "' is not set to 'true'");
            return;
        }

        super.runTest();
    }

    private boolean isRunTest() {
        return Boolean.getBoolean(getRunTestProperty());
    }

    private String getRunTestProperty() {
        return getClass().getName() + ".runTest";
    }

    @Override
    protected void setUp() throws Exception {
        if (!isRunTest()) {
            return;
        }

        super.setUp();
        m_pinger = new Pinger();
        m_goodHost = InetAddress.getByName("www.google.com");
        m_badHost  = InetAddress.getByName("1.1.1.1");
    }

    public void testSinglePing() throws Exception {
        assertTrue(m_pinger.ping(m_goodHost) > 0);
        Thread.sleep(1000);
    }

    public void testSinglePingFailure() throws Exception {
        assertNull(m_pinger.ping(m_badHost));
        Thread.sleep(1000);
    }

    public void testParallelPing() throws Exception {
        Map<String,Number> ret = m_pinger.parallelPing(m_goodHost, 10);
        ArrayList<Number> items = new ArrayList<Number>();
        for (String key : ret.keySet()) {
            items.add(ret.get(key));
            System.out.println(key + ": " + ret.get(key));
        }
        ret.remove("loss");
        ret.remove("median");
        ret.remove("response-time");
        System.out.println("pings = " + items.size() + ", passed = " + CollectionMath.countNotNull(items) + " (" + CollectionMath.percentNotNull(items) + "%), failed = " + CollectionMath.countNull(items) + " (" + CollectionMath.percentNull(items) + "%), average = " + (CollectionMath.average(items).floatValue() / 1000F) + "ms");
        Thread.sleep(1000);
        assertTrue(CollectionMath.countNotNull(items) > 0);
    }

    public void testParallelPingFailure() throws Exception {
        Map<String,Number> ret = m_pinger.parallelPing(m_badHost, 10);
        ret.remove("loss");
        ret.remove("median");
        ret.remove("response-time");
        ArrayList<Number>items = new ArrayList<Number>();
        System.out.println("pings = " + items.size() + ", passed = " + CollectionMath.countNotNull(items) + " (" + CollectionMath.percentNotNull(items) + "%), failed = " + CollectionMath.countNull(items) + " (" + CollectionMath.percentNull(items) + "%), average = " + (CollectionMath.average(items).floatValue() / 1000F) + "ms");
        Thread.sleep(1000);
        assertTrue(CollectionMath.countNotNull(items) == 0);
    }
}
