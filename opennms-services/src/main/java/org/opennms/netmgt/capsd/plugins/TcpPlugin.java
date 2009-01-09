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
// 2004 May 05: Remove use of SocketChannel and use timed Socket.connect
// 2003 Jul 21: Explicitly close sockets.
// 2003 Jul 18: Fixed exception to enable retries.
// 2003 Jan 31: Cleaned up some unused imports.
// 2003 Jan 29: Added response time
// 2002 Nov 14: Used non-blocking I/O for speed improvements.
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

package org.opennms.netmgt.capsd.plugins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Map;

import org.apache.log4j.Category;
import org.apache.regexp.RE;
import org.apache.regexp.RESyntaxException;
import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.capsd.AbstractPlugin;

/**
 * <P>
 * This class is designed to be used by the capabilities daemon to test for the
 * existance of an TCP server on remote interfaces. The class implements the
 * Plugin interface that allows it to be used along with other plugins by the
 * daemon.
 * </P>
 * 
 * @author <A HREF="mailto:mike@opennms.org">Mike </A>
 * @author <A HREF="mailto:weave@oculan.com">Weaver </A>
 * @author <A HREF="http://www.opennsm.org">OpenNMS </A>
 * 
 * 
 */
public final class TcpPlugin extends AbstractPlugin {

    /**
     * The protocol supported by the plugin
     */
    private final static String PROTOCOL_NAME = "TCP";

    /**
     * Default number of retries for TCP requests
     */
    private final static int DEFAULT_RETRY = 0;

    /**
     * Default timeout (in milliseconds) for TCP requests
     */
    private final static int DEFAULT_TIMEOUT = 5000; // in milliseconds

    /**
     * <P>
     * Test to see if the passed host-port pair is the endpoint for a TCP
     * server. If there is a TCP server at that destination then a value of true
     * is returned from the method. Otherwise a false value is returned to the
     * caller. In order to return true the remote host must generate a banner
     * line which contains the text from the bannerMatch argument.
     * </P>
     * 
     * @param host
     *            The remote host to connect to.
     * @param port
     *            The remote port on the host.
     * @param bannerResult
     *            Banner line generated by the remote host must contain this
     *            text.
     * 
     * @return True if a connection is established with the host and the banner
     *         line contains the bannerMatch text.
     */
    private boolean isServer(InetAddress host, int port, int retries, int timeout, RE regex, StringBuffer bannerResult) {
        Category log = ThreadCategory.getInstance(getClass());

        boolean isAServer = false;
        for (int attempts = 0; attempts <= retries && !isAServer; attempts++) {
            Socket socket = null;
            try {
                // create a connected socket
                //
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), timeout);
                socket.setSoTimeout(timeout);
                log.debug("TcpPlugin: connected to host: " + host + " on port: " + port);

                // If banner matching string is null or wildcard ("*") then we
                // only need to test connectivity and we've got that!
                //
                if (regex == null) {
                    isAServer = true;
                } else {
                    // get a line reader
                    //
                    BufferedReader lineRdr = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Read the server's banner line ouptput and validate it
                    // against
                    // the bannerMatch parameter to determine if this interface
                    // supports the
                    // service.
                    //
                    String response = lineRdr.readLine();
                    if (regex.match(response)) {
                        if (log.isDebugEnabled())
                            log.debug("isServer: matching response=" + response);
                        isAServer = true;
                        if (bannerResult != null)
                            bannerResult.append(response);
                    } else {
                        // Got a response but it didn't match...no need to
                        // attempt retries
                        isAServer = false;
                        if (log.isDebugEnabled())
                            log.debug("isServer: NON-matching response=" + response);
                        break;
                    }
                }
            } catch (ConnectException e) {
                // Connection refused!! Continue to retry.
                //
                log.debug("TcpPlugin: Connection refused to " + host.getHostAddress() + ":" + port);
                isAServer = false;
            } catch (NoRouteToHostException e) {
                // No Route to host!!!
                //
                e.fillInStackTrace();
                log.info("TcpPlugin: Could not connect to host " + host.getHostAddress() + ", no route to host", e);
                isAServer = false;
                throw new UndeclaredThrowableException(e);
            } catch (InterruptedIOException e) {
                // This is an expected exception
                //
                log.debug("TcpPlugin: did not connect to host within timeout: " + timeout + " attempt: " + attempts);
                isAServer = false;
            } catch (IOException e) {
                log.info("TcpPlugin: An expected I/O exception occured connecting to host " + host.getHostAddress() + " on port " + port, e);
                isAServer = false;
            } catch (Throwable t) {
                isAServer = false;
                log.warn("TcpPlugin: An undeclared throwable exception was caught connecting to host " + host.getHostAddress() + " on port " + port, t);
            } finally {
                try {
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                }
            }
        }

        //
        // return the success/failure of this
        // attempt to contact an ftp server.
        //
        return isAServer;
    }

    /**
     * Returns the name of the protocol that this plugin checks on the target
     * system for support.
     * 
     * @return The protocol name for this plugin.
     */
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    /**
     * Returns true if the protocol defined by this plugin is supported. If the
     * protocol is not supported then a false value is returned to the caller.
     * 
     * @param address
     *            The address to check for support.
     * 
     * @return True if the protocol is supported by the address.
     * 
     * @throws java.lang.UnsupportedOperationException
     *             This is always thrown by this plugin.
     */
    public boolean isProtocolSupported(InetAddress address) {
        throw new UnsupportedOperationException("Undirected TCP checking not supported");
    }

    /**
     * Returns true if the protocol defined by this plugin is supported. If the
     * protocol is not supported then a false value is returned to the caller.
     * The qualifier map passed to the method is used by the plugin to return
     * additional information by key-name. These key-value pairs can be added to
     * service events if needed.
     * 
     * @param address
     *            The address to check for support.
     * @param qualifiers
     *            The map where qualification are set by the plugin.
     * 
     * @return True if the protocol is supported by the address.
     */
    public boolean isProtocolSupported(InetAddress address, Map<String, Object> qualifiers) {
        int retries = DEFAULT_RETRY;
        int timeout = DEFAULT_TIMEOUT;
        int port = -1;

        String banner = null;
        String match = null;

        if (qualifiers != null) {
            retries = ParameterMap.getKeyedInteger(qualifiers, "retry", DEFAULT_RETRY);
            timeout = ParameterMap.getKeyedInteger(qualifiers, "timeout", DEFAULT_TIMEOUT);
            port = ParameterMap.getKeyedInteger(qualifiers, "port", -1);
            banner = ParameterMap.getKeyedString(qualifiers, "banner", null);
            match = ParameterMap.getKeyedString(qualifiers, "match", null);
        }

        // verify the port
        //
        if (port == -1)
            throw new IllegalArgumentException("The port must be specified when doing TCP discovery");

        try {
            StringBuffer bannerResult = null;
            RE regex = null;
            if (match == null && (banner == null || banner.equals("*"))) {
                regex = null;
            } else if (match != null) {
                regex = new RE(match);
                bannerResult = new StringBuffer();
            } else if (banner != null) {
                regex = new RE(banner);
                bannerResult = new StringBuffer();
            }

            boolean result = isServer(address, port, retries, timeout, regex, bannerResult);
            if (result && qualifiers != null) {
                if (bannerResult != null && bannerResult.length() > 0)
                    qualifiers.put("banner", bannerResult.toString());
            }

            return result;
        } catch (RESyntaxException e) {
            throw new java.lang.reflect.UndeclaredThrowableException(e);
        }
    }
}
