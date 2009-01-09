//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2002-2003 The OpenNMS Group, Inc. All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Modifications:
//
// 2004 May 05: Switch from SocketChannel to Socket with connection timeout.
// 2003 Jul 21: Explicitly closed socket.
// 2003 Jul 18: Enabled retries for monitors.
// 2003 Jun 11: Added a "catch" for RRD update errors. Bug #748.
// 2003 Jan 31: Added the ability to imbed RRA information in poller packages.
// 2003 Jan 31: Cleaned up some unused imports.
// 2003 Jan 29: Added response times to certain monitors.
// 2002 Nov 14: Used non-blocking I/O socket channel classes.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp. All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
//      OpenNMS Licensing <license@opennms.org>
//      http://www.opennms.org/
//      http://www.opennms.com/
//
// Tab Size = 8
//

package org.opennms.netmgt.poller.monitors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Map;

import org.apache.log4j.Level;
import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.TimeoutTracker;
import org.opennms.netmgt.model.PollStatus;
import org.opennms.netmgt.poller.Distributable;
import org.opennms.netmgt.poller.MonitoredService;

/**
 * This class is designed to be used by the service poller framework to test the
 * availability of the Citrix service on remote interfaces. The class implements
 * the ServiceMonitor interface that allows it to be used along with other
 * plug-ins by the service poller framework.
 * 
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="mailto:jason@opennms.org">Jason </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 * 
 * 
 */
@Distributable
final public class CitrixMonitor extends IPv4Monitor {

    /**
     * Default FTP port.
     */
    private static final int DEFAULT_PORT = 1494;

    /**
     * Default retries.
     */
    private static final int DEFAULT_RETRY = 0;

    /**
     * Default timeout. Specifies how long (in milliseconds) to block waiting
     * for data from the monitored interface.
     */
    private static final int DEFAULT_TIMEOUT = 3000; // 3 second timeout on
                                                        // read()

    /**
     * Poll the specified address for Citrix service availability.
     * 
     * During the poll an attempt is made to connect on the specified port (by
     * default port 1494). If the connection request is successful, the banner
     * line generated by the interface is parsed and if the extracted return
     * code indicates that we are talking to an Citrix server ('ICA' appears in
     * the response) we set the service status to SERVICE_AVAILABLE and return.
     * @param parameters
     *            The package parameters (timeout, retry, etc...) to be used for
     *            this poll.
     * @param iface
     *            The network interface to test the service on.
     * @return The availibility of the interface and if a transition event
     *         should be supressed.
     * 
     */
    public PollStatus poll(MonitoredService svc, Map parameters) {

        // Get the category logger
        //
        // get the parameters
        //
        
        TimeoutTracker timeoutTracker = new TimeoutTracker(parameters, DEFAULT_RETRY, DEFAULT_TIMEOUT);
        
        int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);

        // Extract the address
        //
        InetAddress ipv4Addr = (InetAddress) svc.getAddress();
        String host = ipv4Addr.getHostAddress();

        if (log().isDebugEnabled())
            log().debug("CitrixMonitor.poll: Polling interface: " + host + timeoutTracker);

        PollStatus serviceStatus = PollStatus.unavailable();

        for(timeoutTracker.reset(); timeoutTracker.shouldRetry() && !serviceStatus.isAvailable(); timeoutTracker.nextAttempt()) {
            Socket socket = null;
            try {
                
                timeoutTracker.startAttempt();
                
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipv4Addr, port), timeoutTracker.getConnectionTimeout());
                socket.setSoTimeout(timeoutTracker.getSoTimeout());
                log().debug("CitrixMonitor: connected to host: " + host + " on port: " + port);

                // We're connected, so upgrade status to unresponsive

                // Allocate a line reader
                //
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                StringBuffer buffer = new StringBuffer();

                // Not an infinite loop...socket timeout will break this out
                // of the loop if "ICA" string is never read.
                //
                while (!serviceStatus.isAvailable()) {
                    buffer.append((char) reader.read());
                    if (buffer.toString().indexOf("ICA") > -1) {
                        serviceStatus = PollStatus.available(timeoutTracker.elapsedTimeInMillis());
                    } else {
                        serviceStatus = PollStatus.unavailable("magic cookie 'ICA' missing from service greeting.");
                    }
                }
            } catch (ConnectException e) {

                // Connection refused!! Continue to retry.
            	serviceStatus = logDown(Level.DEBUG, "Connection refused by host "+host, e);
            	
            } catch (NoRouteToHostException e) {

            	// No route to host!! Try retries anyway in case strict timeouts are enabled
                serviceStatus = logDown(Level.INFO, "Unable to test host " + host + ", no route available", e);
            
            } catch (InterruptedIOException e) {
            	
            	serviceStatus = logDown(Level.DEBUG, "did not connect to host " + host +" within timeout: " + timeoutTracker);
                		
            } catch (IOException e) {
            	
            	serviceStatus = logDown(Level.INFO, "Error communicating with host " + host, e);
                
            } catch (Throwable t) {

                serviceStatus = logDown(Level.WARN, "Undeclared throwable exception caught contacting host " + host, t);
                
            } finally {
                try {
                    if (socket != null) {
                        socket.close();
                        socket = null;
                    }
                } catch (IOException e) {
                }
            }
            
        }

        //
        // return the status of the service
        //
        return serviceStatus;
    }

}
