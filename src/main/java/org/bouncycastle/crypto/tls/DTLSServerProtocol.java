package org.bouncycastle.crypto.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.util.Arrays;

public class DTLSServerProtocol extends DTLSProtocol {

    protected boolean verifyRequests = true;

    public DTLSServerProtocol(SecureRandom secureRandom) {
        super(secureRandom);
    }

    public boolean getVerifyRequests() {
        return verifyRequests;
    }

    public void setVerifyRequests(boolean verifyRequests) {
        this.verifyRequests = verifyRequests;
    }

    public DTLSTransport accept(TlsServer server, DatagramTransport transport) throws IOException {

        if (server == null)
            throw new IllegalArgumentException("'server' cannot be null");
        if (transport == null)
            throw new IllegalArgumentException("'transport' cannot be null");

        SecurityParameters securityParameters = new SecurityParameters();
        securityParameters.entity = ConnectionEnd.server;
        securityParameters.serverRandom = TlsProtocol.createRandomBlock(secureRandom);

        ServerHandshakeState state = new ServerHandshakeState();
        state.server = server;
        state.serverContext = new TlsServerContextImpl(secureRandom, securityParameters);
        server.init(state.serverContext);

        DTLSRecordLayer recordLayer = new DTLSRecordLayer(transport, state.serverContext, ContentType.handshake);

        // TODO Need to handle sending of HelloVerifyRequest without entering a full connection

        DTLSReliableHandshake handshake = new DTLSReliableHandshake(state.serverContext, recordLayer);

        DTLSReliableHandshake.Message clientMessage = handshake.receiveMessage();

        {
            // NOTE: After receiving a record from the client, we discover the record layer version
            ProtocolVersion client_version = recordLayer.getDiscoveredPeerVersion();
            // TODO Read RFCs for guidance on the expected record layer version number
            state.serverContext.setClientVersion(client_version);
        }

        if (clientMessage.getType() == HandshakeType.client_hello) {
            processClientHello(state, clientMessage.getBody());
        } else {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        byte[] serverHelloBody = generateServerHello(state);
        handshake.sendMessage(HandshakeType.server_hello, serverHelloBody);

        // TODO This block could really be done before actually sending the hello
        {
            securityParameters.prfAlgorithm = TlsProtocol.getPRFAlgorithm(state.selectedCipherSuite);
            securityParameters.compressionAlgorithm = state.selectedCompressionMethod;

            /*
             * RFC 5264 7.4.9. Any cipher suite which does not explicitly specify verify_data_length
             * has a verify_data_length equal to 12. This includes all existing cipher suites.
             */
            securityParameters.verifyDataLength = 12;

            handshake.notifyHelloComplete();
        }

        Vector serverSupplementalData = state.server.getServerSupplementalData();
        if (serverSupplementalData != null) {
            byte[] supplementalDataBody = generateSupplementalData(serverSupplementalData);
            handshake.sendMessage(HandshakeType.supplemental_data, supplementalDataBody);
        }

        state.keyExchange = state.server.getKeyExchange();
        state.keyExchange.init(state.serverContext);

        TlsCredentials serverCredentials = state.server.getCredentials();
        if (serverCredentials == null) {
            state.keyExchange.skipServerCredentials();
        } else {
            state.keyExchange.processServerCredentials(serverCredentials);

            byte[] certificateBody = generateCertificate(serverCredentials.getCertificate());
            handshake.sendMessage(HandshakeType.certificate, certificateBody);
        }

        byte[] serverKeyExchange = state.keyExchange.generateServerKeyExchange();
        if (serverKeyExchange != null) {
            handshake.sendMessage(HandshakeType.server_key_exchange, serverKeyExchange);
        }

        if (serverCredentials != null) {
            state.certificateRequest = state.server.getCertificateRequest();
            if (state.certificateRequest != null) {
                state.keyExchange.validateCertificateRequest(state.certificateRequest);

                byte[] certificateRequestBody = generateCertificateRequest(state, state.certificateRequest);
                handshake.sendMessage(HandshakeType.certificate_request, certificateRequestBody);
            }
        }

        handshake.sendMessage(HandshakeType.server_hello_done, TlsUtils.EMPTY_BYTES);

        clientMessage = handshake.receiveMessage();

        if (clientMessage.getType() == HandshakeType.supplemental_data) {
            processClientSupplementalData(state, clientMessage.getBody());
            clientMessage = handshake.receiveMessage();
        } else {
            state.server.processClientSupplementalData(null);
        }

        if (clientMessage.getType() == HandshakeType.certificate) {
            if (state.certificateRequest == null) {
                throw new TlsFatalAlert(AlertDescription.unexpected_message);
            }
            processClientCertificate(state, clientMessage.getBody());
            clientMessage = handshake.receiveMessage();
        } else {
            // Okay, Certificate is optional
            state.keyExchange.skipClientCredentials();
        }

        if (clientMessage.getType() == HandshakeType.client_key_exchange) {
            processClientKeyExchange(state, clientMessage.getBody());
        } else {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        recordLayer.initPendingEpoch(state.server.getCipher());

        // NOTE: Calculated exclusive of the actual Finished message from the client
        byte[] clientFinishedHash = handshake.getCurrentHash();
        clientMessage = handshake.receiveMessage();

        // TODO Check whether the client Certificate has signing capability

        if (clientMessage.getType() == HandshakeType.certificate_verify) {
            processCertificateVerify(state, clientMessage.getBody());

            // TODO Integrate verify_data determination into DTLSReliableHandshake to avoid
            // re-calculating this
            clientFinishedHash = handshake.getCurrentHash();
            clientMessage = handshake.receiveMessage();
        } else {
            // TODO Inform state.server that there's no CertificateVerify
        }

        if (clientMessage.getType() == HandshakeType.finished) {
            byte[] expectedClientVerifyData = TlsUtils.calculateVerifyData(state.serverContext, "client finished",
                clientFinishedHash);
            processFinished(clientMessage.getBody(), expectedClientVerifyData);
        } else {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        if (state.expectSessionTicket) {
            NewSessionTicket newSessionTicket = state.server.getNewSessionTicket();
            byte[] newSessionTicketBody = generateNewSessionTicket(state, newSessionTicket);
            handshake.sendMessage(HandshakeType.session_ticket, newSessionTicketBody);
        }

        // NOTE: Calculated exclusive of the Finished message itself
        byte[] serverVerifyData = TlsUtils.calculateVerifyData(state.serverContext, "server finished",
            handshake.getCurrentHash());
        handshake.sendMessage(HandshakeType.finished, serverVerifyData);

        handshake.finish();

        state.server.notifyHandshakeComplete();

        return new DTLSTransport(recordLayer, state.server);
    }

    protected byte[] generateCertificateRequest(ServerHandshakeState state, CertificateRequest certificateRequest)
        throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        certificateRequest.encode(buf);
        return buf.toByteArray();
    }

    protected byte[] generateNewSessionTicket(ServerHandshakeState state, NewSessionTicket newSessionTicket)
        throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        newSessionTicket.encode(buf);
        return buf.toByteArray();
    }

    protected byte[] generateServerHello(ServerHandshakeState state) throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        ProtocolVersion server_version = state.server.getServerVersion();
        if (!server_version.isEqualOrEarlierVersionOf(state.serverContext.getClientVersion())) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        // TODO Read RFCs for guidance on the expected record layer version number
        // recordStream.setReadVersion(server_version);
        // recordStream.setWriteVersion(server_version);
        // recordStream.setRestrictReadVersion(true);
        state.serverContext.setServerVersion(server_version);

        TlsUtils.writeVersion(state.serverContext.getServerVersion(), buf);

        buf.write(state.serverContext.getSecurityParameters().serverRandom);

        /*
         * The server may return an empty session_id to indicate that the session will not be cached
         * and therefore cannot be resumed.
         */
        TlsUtils.writeUint8((short) 0, buf);

        state.selectedCipherSuite = state.server.getSelectedCipherSuite();
        if (!TlsProtocol.arrayContains(state.offeredCipherSuites, state.selectedCipherSuite)
            || state.selectedCipherSuite == CipherSuite.TLS_NULL_WITH_NULL_NULL
            || state.selectedCipherSuite == CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        validateSelectedCipherSuite(state.selectedCipherSuite, AlertDescription.internal_error);

        state.selectedCompressionMethod = state.server.getSelectedCompressionMethod();
        if (!TlsProtocol.arrayContains(state.offeredCompressionMethods, state.selectedCompressionMethod)) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        TlsUtils.writeUint16(state.selectedCipherSuite, buf);
        TlsUtils.writeUint8(state.selectedCompressionMethod, buf);

        state.serverExtensions = state.server.getServerExtensions();

        /*
         * RFC 5746 3.6. Server Behavior: Initial Handshake
         */
        if (state.secure_renegotiation) {

            boolean noRenegExt = state.serverExtensions == null
                || !state.serverExtensions.containsKey(TlsProtocol.EXT_RenegotiationInfo);

            if (noRenegExt) {
                /*
                 * Note that sending a "renegotiation_info" extension in response to a ClientHello
                 * containing only the SCSV is an explicit exception to the prohibition in RFC 5246,
                 * Section 7.4.1.4, on the server sending unsolicited extensions and is only allowed
                 * because the client is signaling its willingness to receive the extension via the
                 * TLS_EMPTY_RENEGOTIATION_INFO_SCSV SCSV.
                 */
                if (state.serverExtensions == null) {
                    state.serverExtensions = new Hashtable();
                }

                /*
                 * If the secure_renegotiation flag is set to TRUE, the server MUST include an empty
                 * "renegotiation_info" extension in the ServerHello message.
                 */
                state.serverExtensions.put(TlsProtocol.EXT_RenegotiationInfo,
                    TlsProtocol.createRenegotiationInfo(TlsUtils.EMPTY_BYTES));
            }
        }

        if (state.serverExtensions != null) {
            state.expectSessionTicket = state.serverExtensions.containsKey(TlsProtocol.EXT_SessionTicket);
            TlsProtocol.writeExtensions(buf, state.serverExtensions);
        }

        return buf.toByteArray();
    }

    protected void processClientCertificate(ServerHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        Certificate clientCertificate = Certificate.parse(buf);

        TlsProtocol.assertEmpty(buf);

        state.keyExchange.processClientCertificate(clientCertificate);
    }

    protected void processCertificateVerify(ServerHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        // TODO

        TlsProtocol.assertEmpty(buf);
    }

    protected void processClientHello(ServerHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        // TODO Read RFCs for guidance on the expected record layer version number
        ProtocolVersion client_version = TlsUtils.readVersion(buf);
        if (!client_version.isDTLS()) {
            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
        }

        /*
         * Read the client random
         */
        byte[] client_random = TlsUtils.readFully(32, buf);

        byte[] sessionID = TlsUtils.readOpaque8(buf);
        if (sessionID.length > 32) {
            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
        }

        byte[] cookie = TlsUtils.readOpaque8(buf);

        // TODO RFC 4347 has the cookie length restricted to 32, but not in RFC 6347

        int cipher_suites_length = TlsUtils.readUint16(buf);
        if (cipher_suites_length < 2 || (cipher_suites_length & 1) != 0) {
            throw new TlsFatalAlert(AlertDescription.decode_error);
        }

        /*
         * NOTE: "If the session_id field is not empty (implying a session resumption request) this
         * vector must include at least the cipher_suite from that session."
         */
        state.offeredCipherSuites = TlsUtils.readUint16Array(cipher_suites_length / 2, buf);

        int compression_methods_length = TlsUtils.readUint8(buf);
        if (compression_methods_length < 1) {
            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
        }

        state.offeredCompressionMethods = TlsUtils.readUint8Array(compression_methods_length, buf);

        /*
         * TODO RFC 3546 2.3 If [...] the older session is resumed, then the server MUST ignore
         * extensions appearing in the client hello, and send a server hello containing no
         * extensions.
         */
        state.clientExtensions = TlsProtocol.readExtensions(buf);

        /*
         * TODO RFC 5746 3.4. The client MUST include either an empty "renegotiation_info"
         * extension, or the TLS_EMPTY_RENEGOTIATION_INFO_SCSV signaling cipher suite value in the
         * ClientHello. Including both is NOT RECOMMENDED.
         */

        state.serverContext.setClientVersion(client_version);

        state.server.notifyClientVersion(client_version);

        state.serverContext.getSecurityParameters().clientRandom = client_random;

        state.server.notifyOfferedCipherSuites(state.offeredCipherSuites);
        state.server.notifyOfferedCompressionMethods(state.offeredCompressionMethods);

        /*
         * RFC 5746 3.6. Server Behavior: Initial Handshake
         */
        {
            /*
             * When a ClientHello is received, the server MUST check if it includes the
             * TLS_EMPTY_RENEGOTIATION_INFO_SCSV SCSV. If it does, set the secure_renegotiation flag
             * to TRUE.
             */
            if (TlsProtocol.arrayContains(state.offeredCipherSuites, CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV)) {
                state.secure_renegotiation = true;
            }

            /*
             * The server MUST check if the "renegotiation_info" extension is included in the
             * ClientHello.
             */
            if (state.clientExtensions != null) {
                byte[] renegExtValue = (byte[]) state.clientExtensions.get(TlsProtocol.EXT_RenegotiationInfo);
                if (renegExtValue != null) {
                    /*
                     * If the extension is present, set secure_renegotiation flag to TRUE. The
                     * server MUST then verify that the length of the "renegotiated_connection"
                     * field is zero, and if it is not, MUST abort the handshake.
                     */
                    state.secure_renegotiation = true;

                    if (!Arrays.constantTimeAreEqual(renegExtValue,
                        TlsProtocol.createRenegotiationInfo(TlsUtils.EMPTY_BYTES))) {
                        throw new TlsFatalAlert(AlertDescription.handshake_failure);
                    }
                }
            }
        }

        state.server.notifySecureRenegotiation(state.secure_renegotiation);

        if (state.clientExtensions != null) {
            state.server.processClientExtensions(state.clientExtensions);
        }
    }

    protected void processClientKeyExchange(ServerHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.keyExchange.processClientKeyExchange(buf);

        TlsProtocol.assertEmpty(buf);

        TlsProtocol.establishMasterSecret(state.serverContext, state.keyExchange);
    }

    protected void processClientSupplementalData(ServerHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);
        Vector clientSupplementalData = TlsProtocol.readSupplementalDataMessage(buf);
        state.server.processClientSupplementalData(clientSupplementalData);
    }

    protected static class ServerHandshakeState {
        TlsServer server = null;
        TlsServerContextImpl serverContext = null;
        int[] offeredCipherSuites;
        short[] offeredCompressionMethods;
        Hashtable clientExtensions;
        int selectedCipherSuite = -1;
        short selectedCompressionMethod = -1;
        boolean secure_renegotiation = false;
        boolean expectSessionTicket = false;
        Hashtable serverExtensions = null;
        TlsKeyExchange keyExchange = null;
        CertificateRequest certificateRequest = null;
    }
}
