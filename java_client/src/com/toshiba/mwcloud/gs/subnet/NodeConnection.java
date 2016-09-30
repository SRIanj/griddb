/*
   Copyright (c) 2012 TOSHIBA CORPORATION.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.toshiba.mwcloud.gs.subnet;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Formatter;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.toshiba.mwcloud.gs.GSException;
import com.toshiba.mwcloud.gs.common.BasicBuffer;
import com.toshiba.mwcloud.gs.common.GSConnectionException;
import com.toshiba.mwcloud.gs.common.GSErrorCode;
import com.toshiba.mwcloud.gs.common.GSStatementException;
import com.toshiba.mwcloud.gs.common.GSWrongNodeException;
import com.toshiba.mwcloud.gs.common.LoggingUtils;
import com.toshiba.mwcloud.gs.common.LoggingUtils.BaseGridStoreLogger;
import com.toshiba.mwcloud.gs.common.PropertyUtils;
import com.toshiba.mwcloud.gs.common.PropertyUtils.WrappedProperties;
import com.toshiba.mwcloud.gs.common.Statement;
import com.toshiba.mwcloud.gs.common.StatementResult;

public class NodeConnection implements Closeable {

	public static final int EE_MAGIC_NUMBER = 65021048;

	private static final int DEFAULT_PROTOCOL_VERSION = 10;

	private static final int STATEMENT_TYPE_NUMBER_V2_OFFSET = 100;

	private static final int SPECIAL_PARTITION_ID = 0;

	private static final StatementResult[] STATEMENT_RESULT_CONSTANTS =
			StatementResult.values();

	private static volatile boolean detailErrorMessageEnabled = false;

	private static volatile int protocolVersion = DEFAULT_PROTOCOL_VERSION;

	private static volatile int firstStatementTypeNumber =
			statementToNumber(Statement.CONNECT);

	private static boolean tcpNoDelayEnabled = true;

	private static final BaseGridStoreLogger HEARTBEAT_LOGGER =
			LoggingUtils.getLogger("Heartbeat");

	private static final BaseGridStoreLogger IO_LOGGER =
			LoggingUtils.getLogger("StatementIO");

	private static final Statement STATEMENT_LIST[] = Statement.values();

	private final long statementTimeoutMillis;

	private final long heartbeatTimeoutMillis;

	private final Socket socket;

	private final InputStream input;

	private final OutputStream output;

	private final Integer alternativeVersion;

	private final boolean ipv6Enabled;

	private AuthMode authMode = Challenge.getDefaultMode();

	private boolean responseUnacceptable;

	private long statementId = 0;

	private byte[] pendingResp;

	public NodeConnection(
			InetSocketAddress address, Config config) throws GSException {
		statementTimeoutMillis = (config.statementTimeoutEnabled ?
				config.statementTimeoutMillis : Long.MAX_VALUE);
		heartbeatTimeoutMillis = config.heartbeatTimeoutMillis;

		try {
			socket = new Socket();
			if (tcpNoDelayEnabled) {
				socket.setTcpNoDelay(true);
			}
			socket.connect(address,
					PropertyUtils.timeoutPropertyToIntMillis(
							config.connectTimeoutMillis));
			socket.setSoTimeout(
					PropertyUtils.timeoutPropertyToIntMillis(Math.min(
							statementTimeoutMillis, heartbeatTimeoutMillis)));
			input = socket.getInputStream();
			output = socket.getOutputStream();
		}
		catch (IOException e) {
			throw new GSConnectionException(
					GSErrorCode.BAD_CONNECTION,
					"Failed to connect (address=" + address +
					", reason=" + e.getMessage() + ")", e);
		}

		alternativeVersion = config.alternativeVersion;
		ipv6Enabled = (address.getAddress() instanceof Inet6Address);
	}

	public static void setDetailErrorMessageEnabled(
			boolean detailErrorMessageEnabled) {
		NodeConnection.detailErrorMessageEnabled = detailErrorMessageEnabled;
	}

	public static int getProtocolVersion() {
		return protocolVersion;
	}

	public static boolean isSupportedProtocolVersion(int protocolVersion) {
		switch (protocolVersion) {
		case 1:
		case 2:
		case 3:
		case 4:
		case 5:
		case 6:
		case 8:
			return true;
		default:
			return false;
		}
	}

	public static void setProtocolVersion(int protocolVersion) throws GSException {
		if (!isSupportedProtocolVersion(protocolVersion)) {
			throw new GSException(
					GSErrorCode.ILLEGAL_PARAMETER,
					"Wrong protocol version (version=" + protocolVersion + ")");
		}
		NodeConnection.protocolVersion = protocolVersion;
		firstStatementTypeNumber = statementToNumber(Statement.CONNECT);
	}

	public SocketAddress getRemoteSocketAddress() {
		return socket.getRemoteSocketAddress();
	}

	private static int getEEHeadLength(boolean ipv6Enabled) {
		
		
		
		
		
		if (ipv6Enabled) {
			return 4 * 4 + 16;
		}
		else {
			return 4 * 5;
		}
	}

	public static int getRequestHeadLength(boolean ipv6Enabled) {
		return getRequestHeadLength(ipv6Enabled, false);
	}

	public static int getRequestHeadLength(
			boolean ipv6Enabled, boolean firstStatement) {
		
		
		
		
		final int statementIdSize =
				(isStatementIdLarge(firstStatement) ? 8 : 4);
		return getEEHeadLength(ipv6Enabled) + 4 * 2 + statementIdSize;
	}

	public static void fillRequestHead(boolean ipv6Enabled, BasicBuffer req) {
		fillRequestHead(ipv6Enabled, req, false);
	}

	public static void fillRequestHead(
			boolean ipv6Enabled, BasicBuffer req, boolean firstStatement) {
		req.clear();
		req.prepare(getRequestHeadLength(ipv6Enabled, firstStatement));

		
		req.base().putInt(EE_MAGIC_NUMBER);

		
		if (ipv6Enabled) {
			req.base().putLong(0);
			req.base().putLong(0);
		}
		else {
			req.base().putInt(0);
		}

		
		req.base().putInt(0);

		
		req.base().putInt(-1);

		
		req.base().putInt(0);

		
		req.base().putInt(0);

		
		req.base().putInt(0);

		
		putStatementId(req, 0, firstStatement);
	}

	public static boolean isStatementIdLarge(boolean firstStatement) {
		return !firstStatement && protocolVersion >= 3;
	}

	public static void putStatementId(BasicBuffer req, long statementId) {
		putStatementId(req, statementId, false);
	}

	public static void putStatementId(
			BasicBuffer req, long statementId, boolean firstStatement) {
		if (isStatementIdLarge(firstStatement)) {
			req.putLong(statementId);
		}
		else {
			req.putInt((int) statementId);
		}
	}

	public static long getStatementId(
			BasicBuffer resp, boolean firstStatement) {
		if (isStatementIdLarge(firstStatement)) {
			return resp.base().getLong();
		}
		else {
			return resp.base().getInt();
		}
	}

	public static boolean isOptionalRequestEnabled() {
		return protocolVersion >= 3;
	}

	public static void tryPutEmptyOptionalRequest(BasicBuffer req) {
		if (isOptionalRequestEnabled()) {
			req.putInt(0);
		}
	}

	public void executeStatement(
			Statement statement, int partitionId, long statementId,
			BasicBuffer req, BasicBuffer resp)
			throws GSException {
		executeStatementDirect(statementToNumber(statement),
				partitionId, statementId, req, resp, null);
	}

	public void executeStatementDirect(
			int statementTypeNumber, int partitionId, long statementId,
			BasicBuffer req, BasicBuffer resp, Heartbeat heartbeat)
			throws GSException {
		if (partitionId < 0) {
			throw new GSException(
					GSErrorCode.INTERNAL_ERROR,
					"Internal error by illegal partition ID (" +
					"partitionId=" + partitionId +
					", address=" + getRemoteSocketAddress() + ")");
		}

		final boolean firstStatement =
				(statementTypeNumber == firstStatementTypeNumber);
		final int reqHeadLength =
				getRequestHeadLength(ipv6Enabled, firstStatement);
		final int eeHeadLength = getEEHeadLength(ipv6Enabled);
		final int reqLength = req.base().position();
		final long reqStatementId;
		if (statementId == 0) {
			while (++this.statementId == 0) {
			}
			reqStatementId = this.statementId;
		}
		else {
			reqStatementId = statementId;
		}

		if (IO_LOGGER.isDebugEnabled()) {
			final int n = statementTypeNumber - getStatementNumberOffset();
			final String statementName = (
					0 <= n && n < STATEMENT_LIST.length ?
							STATEMENT_LIST[n].toString() : "(" + n + ")");
			IO_LOGGER.debug(
					"statementIO.started",
					statementName,
					getRemoteSocketAddress(),
					partitionId,
					reqStatementId);
		}

		req.base().position(eeHeadLength - Integer.SIZE / Byte.SIZE);
		req.base().putInt(reqLength - eeHeadLength);
		req.base().putInt(statementTypeNumber);
		req.base().putInt(partitionId);
		putStatementId(req, reqStatementId, firstStatement);

		try {
			output.write(req.base().array(), 0, reqLength);
		}
		catch (IOException e) {
			throw new GSConnectionException(
					GSErrorCode.BAD_CONNECTION,
					"Failed to send message (address=" +
					getRemoteSocketAddress() +
					", reason=" + e.getMessage() + ")", e);
		}
		req.base().clear();
		req.base().position(reqHeadLength);

		if (resp == null) {
			return;
		}

		
		resp.clear();
		resp.prepare(eeHeadLength);

		resp.base().limit(eeHeadLength);
		int readLength;
		try {
			readLength = readFully(resp.base().array(),
					0, eeHeadLength, resp.base().capacity());
		}
		catch (GSException e) {
			if (responseUnacceptable || heartbeat != null || firstStatement) {
				throw e;
			}

			heartbeat = new Heartbeat();
			heartbeat.orgStatementTypeNumber = statementTypeNumber;
			heartbeat.orgStatementId = reqStatementId;
			heartbeat.orgStatementFound = false;

			resp = processHeartbeat(partitionId, resp, heartbeat);
			readLength = eeHeadLength;
		}

		if (resp.base().getInt() != EE_MAGIC_NUMBER) {
			throw new GSConnectionException(
					GSErrorCode.MESSAGE_CORRUPTED,
					"Protocol error by illegal magic number (address=" +
					getRemoteSocketAddress() + ")");
		}
		resp.base().position(eeHeadLength - Integer.SIZE / Byte.SIZE);
		final int respBodyLength = resp.base().getInt();

		
		final int respTotalLength = eeHeadLength + respBodyLength;
		if (readLength > respTotalLength) {
			final int extraLength = readLength - respTotalLength;
			final int orgPendingLength;
			if (pendingResp == null) {
				orgPendingLength = 0;
				pendingResp = new byte[extraLength];
			}
			else {
				orgPendingLength = pendingResp.length;
				final byte[] orgPendingResp = pendingResp;
				pendingResp = new byte[orgPendingLength + extraLength];
				System.arraycopy(orgPendingResp, 0,
						pendingResp, 0, orgPendingResp.length);
			}

			System.arraycopy(
					resp.base().array(), respTotalLength, pendingResp,
					orgPendingLength, extraLength);
		}
		else if (readLength < respTotalLength) {
			final int length = respTotalLength - readLength;

			resp.base().limit(readLength);
			resp.base().position(readLength);
			resp.prepare(length);
			resp.base().position(eeHeadLength);

			readFully(resp.base().array(), readLength, length, length);
		}
		resp.base().limit(respTotalLength);

		final boolean statementIdMatched;

		final int respStatementTypeNumber = resp.base().getInt();
		if (respStatementTypeNumber != statementTypeNumber) {
			if (heartbeat == null || respStatementTypeNumber !=
					heartbeat.orgStatementTypeNumber) {
				throw new GSConnectionException(
						GSErrorCode.MESSAGE_CORRUPTED,
						"Protocol error by illegal statement type (address=" +
						getRemoteSocketAddress() + ")");
			}
			heartbeat.orgStatementFound = true;

			final boolean orgFirstStatement =
					(heartbeat.orgStatementTypeNumber ==
							firstStatementTypeNumber);
			statementIdMatched =
					(getStatementId(resp, orgFirstStatement) ==
							heartbeat.orgStatementId);
		}
		else {
			statementIdMatched =
					(getStatementId(resp, firstStatement) == reqStatementId);
		}

		if (!statementIdMatched) {
			throw new GSConnectionException(
					GSErrorCode.MESSAGE_CORRUPTED,
					"Protocol error by illegal statement ID (address=" +
					getRemoteSocketAddress() + ")");
		}

		final StatementResult result =
				resp.getByteEnum(STATEMENT_RESULT_CONSTANTS);
		if (result != StatementResult.SUCCESS) {
			final GSException remoteException;
			try {
				remoteException =
						readRemoteException(result, resp, partitionId);
			}
			catch (BufferUnderflowException e) {
				throw new GSConnectionException(
						GSErrorCode.MESSAGE_CORRUPTED,
						"Protocol error by invalid remote error message (" +
						"result=" + result +
						", address=" + getRemoteSocketAddress() +
						", reason=" + e.getMessage() + ")", e);
			}
			remoteException.fillInStackTrace();

			if (heartbeat != null &&
					respStatementTypeNumber == firstStatementTypeNumber) {
				throw new GSConnectionException(
						GSErrorCode.BAD_CONNECTION,
						"Connection problem occurred by " +
						"invalid heartbeat response (" +
						"result=" + result +
						", address=" + getRemoteSocketAddress() +
						", reason=" + remoteException.getMessage() + ")",
						remoteException);
			}

			throw remoteException;
		}

		if (heartbeat != null && heartbeat.orgException != null) {
			throw heartbeat.orgException;
		}
	}

	private int readFully(byte[] value, int offset, int length, int maxLength)
			throws GSException {
		int pos = offset;

		if (pendingResp != null) {
			final int consumed = Math.min(pendingResp.length, maxLength);
			System.arraycopy(pendingResp, 0, value, pos, consumed);
			pos += consumed;

			if (pendingResp.length == consumed) {
				pendingResp = null;
			}
			else {
				pendingResp = Arrays.copyOfRange(
						pendingResp, consumed, pendingResp.length);
			}
		}

		final int endPos = offset + length;
		final int maxEndPos = offset + maxLength;
		try {
			for (;;) {
				final int last = input.read(value, pos, maxEndPos - pos);
				if (last < 0) {
					throw new GSConnectionException(
							GSErrorCode.BAD_CONNECTION,
							"Connection unexpectedly terminated (address=" +
							getRemoteSocketAddress() + ")");
				}

				if ((pos += last) >= endPos) {
					return pos - offset;
				}
			}
		}
		catch (GSConnectionException e) {
			throw e;
		}
		catch (IOException e) {
			final boolean timeoutOccurred =
					(e instanceof SocketTimeoutException);
			if (!timeoutOccurred || pos != offset) {
				responseUnacceptable = true;
			}

			if (timeoutOccurred) {
				throw new GSConnectionException(
						GSErrorCode.CONNECTION_TIMEOUT,
						"Connection timed out on receiving (" +
						"receivedSize=" + (pos - offset) +
						", totalSize=" + length +
						", address=" + getRemoteSocketAddress() +
						", reason=" + e.getMessage() + ")", e);
			}

			throw new GSConnectionException(
					GSErrorCode.BAD_CONNECTION,
					"Connection problem occurred on receiving (" +
					"receivedSize=" + (pos - offset) +
					", totalSize=" + length +
					", address=" + getRemoteSocketAddress() +
					", reason=" + e.getMessage() + ")", e);
		}
	}

	private BasicBuffer processHeartbeat(
			int partitionId, BasicBuffer resp, Heartbeat heartbeat)
			throws GSException {
		final BasicBuffer heartbeatBuf = createRequestBuffer();

		final int eeHeadLength = getEEHeadLength(ipv6Enabled);
		final long startTime = System.currentTimeMillis();
		final long initialMillis =
				Math.min(statementTimeoutMillis, heartbeatTimeoutMillis);
		long lastHeartbeatId;
		for (;;) {
			final long elapsedMillis =
					System.currentTimeMillis() - startTime + initialMillis;
			if (elapsedMillis >= statementTimeoutMillis) {
				throw new GSConnectionException(
					GSErrorCode.CONNECTION_TIMEOUT,
					"Connection timed out by statement timeout (" +
					"elapsedMillis=" + elapsedMillis +
					", statementTimeoutMillis=" + statementTimeoutMillis +
					", address=" + getRemoteSocketAddress() + ")");
			}

			if (HEARTBEAT_LOGGER.isInfoEnabled()) {
				final int n = heartbeat.orgStatementTypeNumber -
						getStatementNumberOffset();
				final String statementName = (
						0 <= n && n < STATEMENT_LIST.length ?
								STATEMENT_LIST[n].toString() : "(" + n + ")");
				HEARTBEAT_LOGGER.info(
						"heartbeat.started",
						statementName,
						getRemoteSocketAddress(),
						partitionId,
						heartbeat.orgStatementId,
						elapsedMillis);
			}

			while (++this.statementId == 0) {
			}
			lastHeartbeatId = this.statementId;

			final BasicBuffer heartbeatReq = heartbeatBuf;
			putConnectRequest(heartbeatReq);

			try {
				executeStatementDirect(
						firstStatementTypeNumber, SPECIAL_PARTITION_ID,
						lastHeartbeatId, heartbeatReq, resp, heartbeat);
			}
			catch (GSStatementException e) {
				heartbeat.orgException = e;
			}

			if (heartbeat.orgStatementFound) {
				resp = heartbeatBuf;
				resp.clear();
				resp.prepare(eeHeadLength);
				resp.base().limit(eeHeadLength);
				readFully(resp.base().array(), 0, eeHeadLength, eeHeadLength);

				heartbeat.orgStatementTypeNumber = firstStatementTypeNumber;
				heartbeat.orgStatementId = lastHeartbeatId;

				return resp;
			}

			resp.clear();
			resp.prepare(eeHeadLength);
			resp.base().limit(eeHeadLength);
			try {
				readFully(resp.base().array(), 0, eeHeadLength, eeHeadLength);
				return resp;
			}
			catch (GSException e) {
				if (responseUnacceptable) {
					throw new GSConnectionException(
							GSErrorCode.BAD_CONNECTION,
							"Connection problem occurred after heartbeat (" +
							"elapsedMillis=" + elapsedMillis +
							", address=" + getRemoteSocketAddress() +
							", reason=" + e.getMessage() + ")", e);
				}
				continue;
			}
		}
	}

	private GSException readRemoteException(
			StatementResult result, BasicBuffer resp, int partitionId)
			throws BufferUnderflowException {
		final int count = resp.base().getInt();

		int topCode = 0;
		String topMessage = null;

		int majorCode = 0;
		String majorMessage = null;
		final StringBuilder messageBuilder = new StringBuilder();

		for (int i = 0; i < count; i++) {
			final int code = resp.base().getInt();
			final String message = resp.getString();
			final String typeName = resp.getString();

			final String fileName = resp.getString();
			final String functionName = resp.getString();
			final int line = resp.base().getInt();

			if (i == 0) {
				topCode = code;
				topMessage = message;
			}

			boolean majorCodeUpdated = false;
			if (code != 0 && !typeName.endsWith("PlatformException")) {
				majorCodeUpdated = true;
				majorCode = code;
			}

			if (!detailErrorMessageEnabled) {
				if (!message.isEmpty()) {
					if (majorCodeUpdated) {
						majorMessage = message;
					}
					else if (majorCode == 0) {
						majorMessage = "minorCode=" + code + " : " + message;
					}
				}
				continue;
			}

			if (i > 0) {
				messageBuilder.append(" by ");
			}

			if (typeName.isEmpty()) {
				messageBuilder.append("(Unknown exception)");
			}
			else {
				messageBuilder.append(typeName);
			}

			if (!fileName.isEmpty()) {
				messageBuilder.append(" ").append(fileName);
			}

			if (!functionName.isEmpty()) {
				messageBuilder.append(" ").append(functionName);
			}

			if (line > 0) {
				messageBuilder.append(" line=").append(line);
			}

			if (code != 0) {
				messageBuilder.append(" code=").append(code);
			}

			if (!message.isEmpty()) {
				messageBuilder.append(" : ").append(message);
			}
		}

		String errorName = null;
		if (resp.base().remaining() > 0) {
			errorName = resp.getString();
			if (errorName.isEmpty()) {
				errorName = null;
			}
			else {
				majorCode = topCode;
				majorMessage = topMessage;
			}
		}

		if (!detailErrorMessageEnabled && majorMessage != null) {
			messageBuilder.append(majorMessage);
		}

		if (messageBuilder.length() > 0) {
			messageBuilder.append(" ");
		}
		messageBuilder.append("(address=");
		messageBuilder.append(getRemoteSocketAddress());
		messageBuilder.append(", partitionId=").append(partitionId);
		messageBuilder.append(")");

		switch (result) {
		case STATEMENT_ERROR: {
			return new GSStatementException(
					(majorCode == 0 ? GSErrorCode.BAD_STATEMENT : majorCode),
					errorName, messageBuilder.toString(), null);
		}
		case DENY:
			return new GSWrongNodeException(
					(majorCode == 0 ? GSErrorCode.WRONG_NODE : majorCode),
					errorName, messageBuilder.toString(), null);
		default:
			return new GSConnectionException(
					(majorCode == 0 ? GSErrorCode.BAD_CONNECTION : majorCode),
					errorName, messageBuilder.toString(), null);
		}
	}

	private void putConnectRequest(BasicBuffer req) {
		req.base().position(getRequestHeadLength(ipv6Enabled, true));
		req.putInt(alternativeVersion == null ?
				protocolVersion : alternativeVersion);
	}

	public void connect(BasicBuffer req, BasicBuffer resp) throws GSException {
		req = (req == null ? createRequestBuffer() : req);
		resp = (resp == null ? createOutput() : resp);

		putConnectRequest(req);
		executeStatement(
				Statement.CONNECT, SPECIAL_PARTITION_ID, 0, req, resp);

		authMode = Challenge.getMode(resp);
	}

	public void disconnect(BasicBuffer req, BasicBuffer resp) throws GSException {
		req = (req == null ? createRequestBuffer() : req);

		req.base().position(getRequestHeadLength(ipv6Enabled, false));
		tryPutEmptyOptionalRequest(req);
		executeStatement(
				Statement.DISCONNECT, SPECIAL_PARTITION_ID, 0, req, null);

		closeImmediately();
	}

	public void login(
			BasicBuffer req, BasicBuffer resp, LoginInfo loginInfo)
			throws GSException {
		final Challenge challenge = loginInternal(req, resp, loginInfo, null);

		if (challenge != null) {
			loginInternal(req, resp, loginInfo, challenge);
		}
	}

	private Challenge loginInternal(
			BasicBuffer req, BasicBuffer resp, LoginInfo loginInfo,
			Challenge lastChallenge) throws GSException {
		req = (req == null ? createRequestBuffer() : req);
		resp = (resp == null ? createOutput() : resp);

		req.base().position(getRequestHeadLength(ipv6Enabled, false));

		if (isOptionalRequestEnabled()) {
			final OptionalRequest request = new OptionalRequest();

			if (loginInfo.transactionTimeoutSecs >= 0) {
				request.put(OptionalRequestType.TRANSACTION_TIMEOUT,
						loginInfo.transactionTimeoutSecs);
			}

			if (loginInfo.database != null) {
				request.put(OptionalRequestType.DB_NAME, loginInfo.database);
			}
			request.format(req);
		}

		req.putString(loginInfo.user);
		req.putString(Challenge.build(
				authMode, lastChallenge, loginInfo.passwordDigest));
		req.putInt(PropertyUtils.
				timeoutPropertyToIntSeconds(statementTimeoutMillis));
		req.putBoolean(loginInfo.ownerMode);

		req.putString(loginInfo.clusterName == null ? "" : loginInfo.clusterName);
		Challenge.putRequest(req, authMode, lastChallenge);

		executeStatement(Statement.LOGIN, SPECIAL_PARTITION_ID, 0, req, resp);

		final AuthMode[] resultMode = new AuthMode[] { authMode };
		final Challenge respChallenge =
				Challenge.getResponse(resp, resultMode, lastChallenge);
		authMode = resultMode[0];
		return respChallenge;
	}

	public void reuse(BasicBuffer req, BasicBuffer resp,
			LoginInfo loginInfo) throws GSException {
		
		
		login(req, resp, loginInfo);
	}

	public void logout(BasicBuffer req, BasicBuffer resp) throws GSException {
		req = (req == null ? createRequestBuffer() : req);
		resp = (resp == null ? createOutput() : resp);

		req.base().position(getRequestHeadLength(ipv6Enabled, false));
		tryPutEmptyOptionalRequest(req);
		executeStatement(Statement.LOGOUT, SPECIAL_PARTITION_ID, 0, req, resp);
	}

	public static String getDigest(String pasword) {
		try {
			final MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(pasword.getBytes(BasicBuffer.DEFAULT_CHARSET));

			StringBuilder builder = new StringBuilder();
			for (byte b : md.digest()) {
				builder.append(String.format("%02x", b));
			}

			return builder.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new Error("Internal error while calculating digest", e);
		}
	}

	public static int statementToNumber(Statement statement) {
		return statement.ordinal() + getStatementNumberOffset();
	}

	public static int getStatementNumberOffset() {
		if (protocolVersion < 2) {
			return 0;
		}

		return STATEMENT_TYPE_NUMBER_V2_OFFSET;
	}

	private BasicBuffer createRequestBuffer() {
		final BasicBuffer req = new BasicBuffer(64);
		fillRequestHead(ipv6Enabled, req, false);
		return req;
	}

	private BasicBuffer createOutput() {
		return new BasicBuffer(64);
	}

	public void closeImmediately() throws GSException {
		try {
			socket.close();
		}
		catch (IOException e) {
			throw new GSException(GSErrorCode.BAD_CONNECTION,
					"Connection problem occurred on closing (" +
					"reason=" + e.getMessage() + ")", e);
		}
	}

	public void close() throws GSException {
		try {
			if (!socket.isClosed()) {
				disconnect(null, null);
			}
		}
		finally {
			closeImmediately();
		}
	}

	static class Heartbeat {
		int orgStatementTypeNumber;
		long orgStatementId;
		boolean orgStatementFound;
		GSStatementException orgException;
	}

	public static class Config {

		private static final long CONNECT_TIMEOUT_DEFAULT = 10 * 1000;

		private static final long STATEMENT_TIMEOUT_DEFAULT = 15 * 1000;

		private static final long HEARTBEAT_TIMEOUT_DEFAULT = 10 * 1000;

		private long connectTimeoutMillis = CONNECT_TIMEOUT_DEFAULT;

		private long statementTimeoutMillis = STATEMENT_TIMEOUT_DEFAULT;

		private long heartbeatTimeoutMillis = HEARTBEAT_TIMEOUT_DEFAULT;

		private boolean statementTimeoutEnabled = false;

		private Integer alternativeVersion;

		public void set(Config config) {
			this.connectTimeoutMillis = config.connectTimeoutMillis;
			this.statementTimeoutMillis = config.statementTimeoutMillis;
			this.heartbeatTimeoutMillis = config.heartbeatTimeoutMillis;
			this.statementTimeoutEnabled = config.statementTimeoutEnabled;
			this.alternativeVersion = config.alternativeVersion;
		}

		public boolean set(WrappedProperties props) throws GSException {
			final long connectTimeoutMillis =
					props.getTimeoutProperty(
							"connectTimeout", this.connectTimeoutMillis, true);
			final long statementTimeoutMillis =
					props.getTimeoutProperty(
							"statementTimeout", this.statementTimeoutMillis, true);
			final long heartbeatTimeoutMillis =
					props.getTimeoutProperty(
							"heartbeatTimeout", this.heartbeatTimeoutMillis, true);
			final boolean statementTimeoutEnabled =
					props.getBooleanProperty(
							"statementTimeoutEnabled",
							this.statementTimeoutEnabled, true);
			if (connectTimeoutMillis < 0 || statementTimeoutMillis < 0) {
				throw new GSException(
						GSErrorCode.ILLEGAL_PROPERTY_ENTRY,
						"Negative timeout properties (" +
						"connectTimeoutMillis=" + connectTimeoutMillis +
						", statementTimeoutMillis=" + statementTimeoutMillis +
						", heartbeatTimeoutMillis=" + heartbeatTimeoutMillis +
						")");
			}

			if (connectTimeoutMillis != this.connectTimeoutMillis ||
					statementTimeoutMillis != this.statementTimeoutMillis ||
					heartbeatTimeoutMillis != this.heartbeatTimeoutMillis ||
					statementTimeoutEnabled != this.statementTimeoutEnabled) {
				this.connectTimeoutMillis = connectTimeoutMillis;
				this.statementTimeoutMillis = statementTimeoutMillis;
				this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
				this.statementTimeoutEnabled = statementTimeoutEnabled;
				return true;
			}

			return false;
		}

		public long getConnectTimeoutMillis() {
			return connectTimeoutMillis;
		}

		public long getStatementTimeoutMillis() {
			return statementTimeoutMillis;
		}

		public void setAlternativeVersion(Integer alternativeVersion) {
			this.alternativeVersion = alternativeVersion;
		}

	}

	public static class LoginInfo {

		public static final String DEFAULT_DATABASE_NAME = null;

		private String user;

		private PasswordDigest passwordDigest;

		private String database;

		private boolean ownerMode;

		private String clusterName;

		private int transactionTimeoutSecs;

		public LoginInfo(
				String user, String password, boolean ownerMode,
				String database, String clusterName,
				long transactionTimeoutMillis) {
			this.user = user;
			this.passwordDigest = Challenge.makeDigest(user, password);
			this.database = database;
			this.ownerMode = ownerMode;
			this.clusterName = clusterName;
			this.transactionTimeoutSecs =
					PropertyUtils.timeoutPropertyToIntSeconds(
							transactionTimeoutMillis);
		}

		public LoginInfo(LoginInfo loginInfo) {
			set(loginInfo);
		}

		public void set(LoginInfo loginInfo) {
			this.user = loginInfo.user;
			this.passwordDigest = loginInfo.passwordDigest;
			this.database = loginInfo.database;
			this.ownerMode = loginInfo.ownerMode;
			this.clusterName = loginInfo.clusterName;
			this.transactionTimeoutSecs = loginInfo.transactionTimeoutSecs;
		}

		public void setUser(String user) {
			this.user = user;
		}

		public void setPassword(String password) {
			this.passwordDigest = Challenge.makeDigest(user, password);
		}

		public void setDatabase(String database) {
			this.database = database;
		}

		public void setOwnerMode(boolean ownerMode) {
			this.ownerMode = ownerMode;
		}

		public boolean isOwnerMode() {
			return ownerMode;
		}

		public String getUser() {
			return user;
		}

		public String getClusterName() {
			return clusterName;
		}

		public String getDatabase() {
			return database;
		}

	}

	public enum AuthMode {
		NONE,
		BASIC,
		CHALLENGE
	}

	public static class Challenge {

		private static final AuthMode DEFAULT_MODE = AuthMode.CHALLENGE;

		private static final String DEFAULT_METHOD = "POST";

		private static final String DEFAULT_REALM = "DB_Auth";

		private static final String DEFAULT_URI = "/";

		private static final String DEFAULT_QOP = "auth";

		private static final Random RANDOM = new SecureRandom();

		private static boolean challengeEnabled = false;

		private final boolean challenging;

		private final String nonce;

		private final String nc;

		private final String opaque;

		private final String baseSalt;

		private String cnonce;

		public Challenge() {
			challenging = false;
			this.nonce = null;
			this.nc = null;
			this.opaque = null;
			this.baseSalt = null;
		}

		public Challenge(
				String nonce, String nc, String opaque, String baseSalt) {
			challenging = true;
			this.nonce = nonce;
			this.nc = nc;
			this.opaque = opaque;
			this.baseSalt = baseSalt;
		}

		public static boolean isChallenging(Challenge challenge) {
			return (challenge != null && challenge.challenging);
		}

		public static PasswordDigest makeDigest(String user, String password) {
			return new PasswordDigest(
					sha256Hash(password),
					sha256Hash(user + ":" + password),
					md5Hash(user + ":" + DEFAULT_REALM + ":" + password));
		}

		public static void putRequest(
				BasicBuffer out, AuthMode mode, Challenge lastChallenge)
				throws GSException {
			if (mode == AuthMode.NONE) {
				return;
			}

			out.putByteEnum(mode);

			final boolean challenged = isChallenging(lastChallenge);
			out.putBoolean(challenged);

			if (challenged) {
				out.putString(lastChallenge.opaque);
				out.putString(lastChallenge.cnonce);
			}
		}

		public static Challenge getResponse(
				BasicBuffer in, AuthMode[] mode, Challenge lastChallenge)
				throws GSException {
			final AuthMode respMode = getMode(in);
			if (respMode != mode[0]) {
				if (respMode == AuthMode.BASIC) {
					mode[0] = respMode;
					return new Challenge();
				}
				throw new GSConnectionException(
						GSErrorCode.MESSAGE_CORRUPTED, "");
			}
			else if (respMode == AuthMode.NONE) {
				return null;
			}

			final boolean respChallenging = in.getBoolean();
			final boolean challenging = isChallenging(lastChallenge);
			if (respMode != AuthMode.BASIC &&
					!(respChallenging ^ challenging)) {
				throw new GSConnectionException(
						GSErrorCode.MESSAGE_CORRUPTED, "");
			}

			if (respChallenging) {
				final String nonce = in.getString();
				final String nc = in.getString();
				final String opaque = in.getString();
				final String baseSalt = in.getString();

				return new Challenge(nonce, nc, opaque, baseSalt);
			}

			return null;
		}

		public static String build(
				AuthMode mode, Challenge challenge, PasswordDigest digest) {
			if (!isChallenging(challenge)) {
				if (mode == AuthMode.CHALLENGE) {
					return "";
				}
				else {
					return digest.basicSecret;
				}
			}

			return challenge.build(digest);
		}

		public String getOpaque() {
			return opaque;
		}

		public String getLastCNonce() {
			return cnonce;
		}

		public static String generateCNonce() {
			return randomHexString(4);
		}

		public String build(PasswordDigest digest) {
			final String cnonce = generateCNonce();
			final String result = build(digest, cnonce);

			this.cnonce = cnonce;
			return result;
		}

		public String build(PasswordDigest digest, String cnonce) {
			return "#1#" + getChallengeDigest(digest, cnonce) + "#" +
					getCryptSecret(digest);
		}

		public String getChallengeDigest(
				PasswordDigest digest, String cnonce) {
			final String ha1 = md5Hash(
					digest.challengeBase + ":" + nonce + ":" + cnonce);
			final String ha2 = md5Hash(DEFAULT_METHOD + ":" + DEFAULT_URI);

			return md5Hash(
					ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" +
					DEFAULT_QOP + ":" + ha2);
		}

		public String getCryptSecret(PasswordDigest digest) {
			return sha256Hash(baseSalt + ":" + digest.cryptBase);
		}

		public static String sha256Hash(String value) {
			return hash(value, "SHA-256");
		}

		public static String md5Hash(String value) {
			return hash(value, "MD5");
		}

		public static String hash(String value, String algorithm) {
			final MessageDigest md;
			try {
				md = MessageDigest.getInstance(algorithm);
			}
			catch (NoSuchAlgorithmException e) {
				throw new Error("Internal error while calculating digest", e);
			}

			md.update(value.getBytes(BasicBuffer.DEFAULT_CHARSET));

			return bytesToHex(md.digest());
		}

		public static String randomHexString(int bytesSize) {
			final byte[] bytes = new byte[bytesSize];
			getRandom().nextBytes(bytes);
			return bytesToHex(bytes);
		}

		public static String bytesToHex(byte[] bytes) {
			final Formatter formatter =
					new Formatter(new StringBuilder(), Locale.US);

			for (byte b : bytes) {
				formatter.format("%02x", b);
			}

			return formatter.toString();
		}

		public static Random getRandom() {
			return RANDOM;
		}

		public static AuthMode getDefaultMode() {
			if (challengeEnabled) {
				return DEFAULT_MODE;
			}
			else {
				return AuthMode.NONE;
			}
		}

		public static AuthMode getMode(BasicBuffer in) throws GSException {
			if (challengeEnabled && in.base().remaining() > 0) {
				return in.getByteEnum(AuthMode.class);
			}
			return AuthMode.NONE;
		}

	}

	public static class PasswordDigest {

		private final String basicSecret;

		private final String cryptBase;

		private final String challengeBase;

		public PasswordDigest(
				String basicSecret, String cryptSecret, String challengeBase) {
			this.basicSecret = basicSecret;
			this.cryptBase = cryptSecret;
			this.challengeBase = challengeBase;
		}

		public String getBasicSecret() {
			return basicSecret;
		}

	}

	public enum OptionalRequestType {

		TRANSACTION_TIMEOUT(1, Integer.class),
		FOR_UPDATE(2, Boolean.class),
		CONTAINER_LOCK_REQUIRED(3, Boolean.class),
		SYSTEM_MODE(4, Boolean.class),
		DB_NAME(5, String.class),
		CONTAINER_ATTRIBUTE(6, Integer.class),
		ROW_INSERT_UPDATE(7, Integer.class),
		REQUEST_MODULE_TYPE(8, Byte.class),
		STATEMENT_TIMEOUT(10001, Integer.class),
		FETCH_LIMIT(10002, Long.class),
		FETCH_SIZE(10003, Long.class);

		private final int id;

		private final Class<?> valueType;

		private OptionalRequestType(int id, Class<?> valueType) {
			this.id = id;
			this.valueType = valueType;
		}

		public int id() {
			return id;
		}

		public Class<?> valueType() {
			return valueType;
		}

	}

	public static class OptionalRequest {

		private final Map<OptionalRequestType, Object> requestMap =
				new EnumMap<OptionalRequestType, Object>(
						OptionalRequestType.class);

		public void clear() {
			requestMap.clear();
		}

		public void put(OptionalRequestType type, Object value) {
			requestMap.put(type, type.valueType().cast(value));
		}

		public void format(BasicBuffer req) {
			if (requestMap.isEmpty()) {
				req.putInt(0);
				return;
			}

			final int headPos = req.base().position();
			req.putInt(0);

			final int bodyPos = req.base().position();
			for (Map.Entry<OptionalRequestType, Object> entry :
					requestMap.entrySet()) {
				req.putShort((short) (entry.getKey().id()));

				final Class<?> valueType = entry.getKey().valueType();
				if (valueType == Integer.class) {
					req.putInt((Integer) entry.getValue());
				}
				else if (valueType == Long.class) {
					req.putLong((Long) entry.getValue());
				}
				else if (valueType == Boolean.class) {
					req.putBoolean((Boolean) entry.getValue());
				}
				else if (valueType == String.class) {
					req.putString((String) entry.getValue());
				}
				else if (valueType == Byte.class) {
					req.put((Byte) entry.getValue());
				}
				else {
					throw new Error("Internal error by illegal value type");
				}
			}

			final int endPos = req.base().position();
			req.base().position(headPos);
			req.putInt(endPos - bodyPos);
			req.base().position(endPos);
		}

	}

}
