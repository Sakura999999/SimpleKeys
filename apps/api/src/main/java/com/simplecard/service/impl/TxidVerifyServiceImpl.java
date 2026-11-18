package com.simplecard.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.Order;
import com.simplecard.entity.UnmatchedTransaction;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.UnmatchedTransactionRepository;
import com.simplecard.service.TxidVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TxidVerifyServiceImpl implements TxidVerifyService {

    private final UnmatchedTransactionRepository unmatchedTransactionRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * TRC20 USDT 合约地址
     */
    private static final String TRON_USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    /**
     * BEP20 USDT 合约地址（BSC）
     */
    private static final String BSC_USDT_CONTRACT = "0x55d398326f99059ff775485246999027b3197955";

    @Override
    public VerifyDetail verifyAndProcess(Order order, String txid) {
        String chain = order.getUsdtChain();

        // ---- Step 1: 调用链上 API 查询交易 ----
        ChainTransaction tx;
        try {
            tx = queryTransaction(chain, txid);
        } catch (Exception e) {
            if (e instanceof org.springframework.web.client.HttpStatusCodeException http && http.getStatusCode().value()==429)
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"链上查询繁忙，请稍后重试",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            if (e instanceof org.springframework.web.client.RestClientException)
                throw new BusinessException(ErrorCode.MAINTENANCE,"链上查询暂不可用，请稍后重试",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            log.warn("Chain lookup failed: order={}, error={}", order.getId(), e.getClass().getSimpleName());
            saveUnmatched(order, txid, chain, VerifyResult.PENDING_REVIEW,
                    "CHAIN_API_ERROR", null, null, null);
            return new VerifyDetail(VerifyResult.PENDING_REVIEW,
                    "CHAIN_API_ERROR", null, null, null, false);
        }

        // ---- Step 2: 交易是否存在且已确认 ----
        if (tx == null || !tx.confirmed) {
            saveUnmatched(order, txid, chain, VerifyResult.AUTO_REJECTED,
                    "TX_NOT_FOUND_OR_UNCONFIRMED", null, null, null);
            return new VerifyDetail(VerifyResult.AUTO_REJECTED,
                    "TX_NOT_FOUND_OR_UNCONFIRMED",
                    tx != null ? tx.from : null, tx != null ? tx.to : null,
                    tx != null ? tx.amount : null, false);
        }

        // ---- Step 3: 收款地址是否匹配我方钱包 ----
        if (!sameRecipient(chain, tx.to, order.getUsdtWalletAddress())) {
            saveUnmatched(order, txid, chain, VerifyResult.AUTO_REJECTED,
                    "ADDRESS_MISMATCH", tx.from, tx.to, tx.amount);
            return new VerifyDetail(VerifyResult.AUTO_REJECTED,
                    "ADDRESS_MISMATCH", tx.from, tx.to, tx.amount, true);
        }

        // ---- Step 4: Token 合约是否为 USDT ----
        // F5: 必须校验 contractAddress 非 null — 否则原生代币（TRX/BNB）转账会绕过此检查
        if (!isUsdtContract(chain, tx.contractAddress)) {
            saveUnmatched(order, txid, chain, VerifyResult.AUTO_REJECTED,
                    "NOT_USDT", tx.from, tx.to, tx.amount);
            return new VerifyDetail(VerifyResult.AUTO_REJECTED,
                    "NOT_USDT", tx.from, tx.to, tx.amount, true);
        }

        // ---- Step 5: 交易时间校验（防止历史交易 TXID 重放攻击） ----
        if (tx.blockTimestamp == null) {
            // 无法获取区块时间戳 → fail-safe 转人工审核，不自动通过
            log.warn("TXID verify: block timestamp unavailable, txid={}, orderId={}", txid, order.getId());
            saveUnmatched(order, txid, chain, VerifyResult.PENDING_REVIEW,
                    "BLOCK_TIME_UNAVAILABLE", tx.from, tx.to, tx.amount);
            return new VerifyDetail(VerifyResult.PENDING_REVIEW,
                    "BLOCK_TIME_UNAVAILABLE", tx.from, tx.to, tx.amount, true);
        }
        long orderCreatedEpoch = order.getCreatedAt()
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        if (tx.blockTimestamp < orderCreatedEpoch) {
            log.warn("TXID verify: transaction too old, txid={}, txTime={}, orderCreated={}, orderId={}",
                    txid, tx.blockTimestamp, orderCreatedEpoch, order.getId());
            saveUnmatched(order, txid, chain, VerifyResult.AUTO_REJECTED,
                    "TX_TOO_OLD", tx.from, tx.to, tx.amount);
            return new VerifyDetail(VerifyResult.AUTO_REJECTED,
                    "TX_TOO_OLD", tx.from, tx.to, tx.amount, true);
        }

        BigDecimal diff = new BigDecimal(order.getUsdtCryptoAmount()).subtract(new BigDecimal(tx.amount)).abs();
        String reason = diff.signum() == 0 ? "ORDER_OWNERSHIP_REVIEW_REQUIRED" : "AMOUNT_MISMATCH:" + diff.toPlainString();
        saveUnmatched(order, txid, chain, VerifyResult.PENDING_REVIEW, reason, tx.from, tx.to, tx.amount);
        return new VerifyDetail(VerifyResult.PENDING_REVIEW, reason, tx.from, tx.to, tx.amount, true);
    }

    /**
     * 链上交易信息
     */
    private record ChainTransaction(
            String from,
            String to,
            String amount,
            String contractAddress,
            boolean confirmed,
            Long blockTimestamp   // 区块时间戳（秒级 Unix timestamp），null 表示获取失败
    ) {}

    /**
     * 查询链上交易
     */
    private boolean sameRecipient(String chain, String actual, String expected) {
        if (actual == null || expected == null) return false;
        return "usdt_bep20".equals(chain) ? actual.equalsIgnoreCase(expected) : actual.equals(expected);
    }

    private boolean isUsdtContract(String chain, String address) {
        if ("usdt_trc20".equals(chain)) return TRON_USDT_CONTRACT.equals(address);
        return "usdt_bep20".equals(chain) && BSC_USDT_CONTRACT.equalsIgnoreCase(address);
    }

    private ChainTransaction queryTransaction(String chain, String txid) {
        if ("usdt_trc20".equals(chain)) {
            return queryTronTransaction(txid);
        } else if ("usdt_bep20".equals(chain)) {
            return queryBscTransaction(txid);
        }
        throw new BusinessException(ErrorCode.TXID_VERIFY_FAILED, "不支持的链类型: " + chain);
    }

    private static final String TRANSFER_TOPIC = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private ChainTransaction queryTronTransaction(String txid) {
        String url = "https://api.trongrid.io/walletsolidity/gettransactioninfobyid";
        String response = restTemplate.postForObject(url, Map.of("value", txid), String.class);
        if (response == null || response.isBlank()) return null;

        try {
            var receipt = objectMapper.readTree(response);
            if (!txid.equalsIgnoreCase(receipt.path("id").asText())
                    || "FAILED".equals(receipt.path("result").asText())
                    || !"SUCCESS".equals(receipt.path("receipt").path("result").asText())
                    || !receipt.path("blockTimeStamp").isIntegralNumber()) return null;

            ChainTransaction transfer = null;
            for (var log : receipt.path("log")) {
                String emitter = log.path("address").asText();
                if (!emitter.matches("(?i)[a-f0-9]{40}") || !TRON_USDT_CONTRACT.equals(hexToTronBase58(emitter))) continue;
                var topics = log.path("topics");
                if (topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.path(0).asText())) continue;
                String from = topics.path(1).asText();
                String to = topics.path(2).asText();
                String data = log.path("data").asText();
                if (!from.matches("(?i)0{24}[a-f0-9]{40}") || !to.matches("(?i)0{24}[a-f0-9]{40}")
                        || !data.matches("(?i)[a-f0-9]{64}")) return null;
                // The current settlement model conservatively handles one USDT transfer per hash.
                if (transfer != null) return null;
                BigDecimal amount = new BigDecimal(new BigInteger(data, 16), 6);
                transfer = new ChainTransaction(hexToTronBase58(from.substring(24)), hexToTronBase58(to.substring(24)),
                        amount.toPlainString(), TRON_USDT_CONTRACT, true, receipt.path("blockTimeStamp").asLong() / 1000);
            }
            return transfer;
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Invalid TRON receipt", invalid);
        }
    }

    /**
     * 通过 BSC 公共 RPC 查询 BEP20 交易。
     * 使用 eth_getTransactionReceipt JSON-RPC 方法获取交易收据，
     * 从 logs 中解析 Transfer 事件提取 from/to/amount/contract。
     * 不依赖 BscScan REST API（已废弃 V1，V2 需付费）。
     */
    private ChainTransaction queryBscTransaction(String txid) {
        String rpcUrl = "https://bsc-dataseed.bnbchain.org/";
        log.info("Querying BSC RPC receipt: txid={}", txid);

        Map<String, Object> rpcRequest = Map.of(
                "jsonrpc", "2.0",
                "method", "eth_getTransactionReceipt",
                "params", List.of(txid),
                "id", 1
        );

        String responseBody = restTemplate.postForObject(rpcUrl, rpcRequest, String.class);
        if (responseBody == null) return null;

        try {
            Map<String, Object> rpcResponse = objectMapper.readValue(responseBody, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> receipt = (Map<String, Object>) rpcResponse.get("result");
            if (receipt == null) return null;

            String statusHex = (String) receipt.get("status");
            if (!"0x1".equals(statusHex) || !txid.equalsIgnoreCase((String) receipt.get("transactionHash"))) return null;

            // 通过 blockNumber 查询区块时间戳
            String blockNumberHex = (String) receipt.get("blockNumber");
            Long blockTimestamp = queryFinalizedBscBlockTimestamp(rpcUrl, blockNumberHex, (String) receipt.get("blockHash"));
            if (blockTimestamp == null) return null;

            // 解析 Transfer 事件日志
            // Transfer(address indexed from, address indexed to, uint256 value)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> logs = (List<Map<String, Object>>) receipt.get("logs");
            if (logs == null) return null;

            ChainTransaction transfer = null;
            for (Map<String, Object> logEntry : logs) {
                @SuppressWarnings("unchecked")
                List<String> topics = (List<String>) logEntry.get("topics");
                String contractAddress = (String) logEntry.get("address");
                if (!BSC_USDT_CONTRACT.equalsIgnoreCase(contractAddress) || topics == null || topics.size() != 3
                        || !("0x" + TRANSFER_TOPIC).equalsIgnoreCase(topics.get(0))) continue;
                if (transfer != null || Boolean.TRUE.equals(logEntry.get("removed"))) return null;
                String data = (String) logEntry.get("data");
                if (!topics.get(1).matches("(?i)0x0{24}[a-f0-9]{40}")
                        || !topics.get(2).matches("(?i)0x0{24}[a-f0-9]{40}")
                        || data == null || !data.matches("(?i)0x[a-f0-9]{64}")) return null;
                // topics[1] = from（32 字节左填充，取后 40 位 hex）
                String from = "0x" + topics.get(1).substring(26);
                // topics[2] = to
                String to = "0x" + topics.get(2).substring(26);
                // data = value（uint256 hex）
                BigInteger rawValue = new BigInteger(data.substring(2), 16);
                // BSC USDT (Binance-Peg BSC-USD) 精度为 18 位
                BigDecimal amount = new BigDecimal(rawValue).movePointLeft(18);

                transfer = new ChainTransaction(from, to, amount.toPlainString(), contractAddress, true, blockTimestamp);
            }

            return transfer;
        } catch (Exception e) {
            log.warn("Failed to parse BSC receipt: error={}", e.getClass().getSimpleName());
            throw new RuntimeException("BSC RPC 解析失败", e);
        }
    }

    /**
     * 通过 BSC RPC 查询区块时间戳。
     * eth_getBlockByNumber 返回区块头，其中 timestamp 为 hex 编码的秒级 Unix 时间戳。
     *
     * @param rpcUrl         BSC RPC 节点地址
     * @param blockNumberHex 区块号（hex，如 "0x541c1a1"）
     * @return 秒级 Unix 时间戳，查询失败返回 null
     */
    private Long queryFinalizedBscBlockTimestamp(String rpcUrl, String blockNumberHex, String blockHash) {
        if (blockNumberHex == null || blockHash == null) return null;
        try {
            if (!"0x38".equals(bscRpc(rpcUrl, "eth_chainId", List.of()))) return null;
            if (!(bscRpc(rpcUrl, "eth_getBlockByNumber", List.of("finalized", false)) instanceof Map<?, ?> finalized)
                    || !(finalized.get("number") instanceof String latest)
                    || Long.decode(blockNumberHex) > Long.decode(latest)) return null;
            if (!(bscRpc(rpcUrl, "eth_getBlockByNumber", List.of(blockNumberHex, false)) instanceof Map<?, ?> canonical)
                    || !(canonical.get("hash") instanceof String canonicalHash)
                    || !blockHash.equalsIgnoreCase(canonicalHash)
                    || !(canonical.get("timestamp") instanceof String timestamp)) return null;
            return Long.decode(timestamp);
        } catch (Exception e) {
            log.warn("Failed to query BSC finality: error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private Object bscRpc(String url, String method, List<?> params) throws java.io.IOException {
        String response = restTemplate.postForObject(url,
                Map.of("jsonrpc", "2.0", "method", method, "params", params, "id", 2), String.class);
        if (response == null) return null;
        return objectMapper.treeToValue(objectMapper.readTree(response).get("result"), Object.class);
    }

    @Override
    public ChainVerifyResult verifyForWebhook(String chain, String txid,
                                               String expectedWalletAddress, String expectedCryptoAmount,
                                               LocalDateTime orderCreatedAt) {
        ChainTransaction tx;
        try {
            tx = queryTransaction(chain, txid);
        } catch (Exception e) {
            log.warn("Webhook chain lookup failed: chain={}, txid={}, error={}", chain, txid, e.getClass().getSimpleName());
            return null; // 查询失败 → 调用方应触发重试
        }

        // 1. 交易是否存在
        if (tx == null || !tx.confirmed()) {
            return null;
        }

        // 2. 收款地址是否匹配
        if (!sameRecipient(chain, tx.to(), expectedWalletAddress)) {
            return new ChainVerifyResult(false,
                    "收款地址不匹配: expected=" + expectedWalletAddress + ", actual=" + tx.to());
        }

        // 3. Token 合约是否为 USDT
        if (!isUsdtContract(chain, tx.contractAddress())) {
            return new ChainVerifyResult(false, "非 USDT 合约交易");
        }

        // 4. 交易时间校验（防止旧交易 TXID 重放，兜底防御）
        if (tx.blockTimestamp() == null) {
            log.warn("Webhook on-chain verify: block timestamp unavailable, txid={}", txid);
            return null; // 无法获取时间戳 → 触发 BEpusdt 重试
        }
        long orderCreatedEpoch = orderCreatedAt
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        if (tx.blockTimestamp() < orderCreatedEpoch) {
            return new ChainVerifyResult(false,
                    "交易时间早于订单创建: txTime=" + tx.blockTimestamp() + ", orderCreated=" + orderCreatedEpoch);
        }

        // 5. 金额是否匹配
        try {
            BigDecimal expected = new BigDecimal(expectedCryptoAmount);
            BigDecimal actual = new BigDecimal(tx.amount());
            if (expected.compareTo(actual) != 0) {
                return new ChainVerifyResult(false,
                        "金额不匹配: expected=" + expectedCryptoAmount + ", actual=" + tx.amount());
            }
        } catch (NumberFormatException e) {
            return new ChainVerifyResult(false, "金额格式异常");
        }

        return new ChainVerifyResult(true, "链上验证通过",
                LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(tx.blockTimestamp()), ZoneId.systemDefault()));
    }

    /**
     * 保存审核记录到 unmatched_transactions 表
     */
    private void saveUnmatched(Order order, String txid, String chain,
                                VerifyResult result, String reason,
                                String from, String to, String amount) {
        UnmatchedTransaction ut = new UnmatchedTransaction();
        ut.setOrderId(order.getId());
        ut.setTxid(txid);
        ut.setChain(chain);
        ut.setOnChainFrom(from);
        ut.setOnChainTo(to);
        ut.setOnChainAmount(amount);
        ut.setExpectedAmount(order.getUsdtCryptoAmount());
        if (amount != null && order.getUsdtCryptoAmount() != null) {
            BigDecimal diff = new BigDecimal(order.getUsdtCryptoAmount())
                    .subtract(new BigDecimal(amount)).abs();
            ut.setAmountDiff(diff.toPlainString());
        }
        ut.setSource("USER_SUBMIT");
        ut.setStatus(result.name());
        ut.setVerifyReason(reason);
        ut.setSubmittedAt(LocalDateTime.now());
        unmatchedTransactionRepository.save(ut);
    }

    // ── Tron 地址格式转换工具 ──

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * 将 TronGrid 事件返回的 hex 地址转换为 Tron Base58Check 地址。
     * TronGrid 事件 result 中的 from/to 为 20 字节 hex（如 0x0854...），
     * 而订单中存储的 Tron 钱包地址为 Base58Check 格式（如 TAjF...）。
     * 转换规则：Base58Check(0x41 + 20字节地址)
     */
    private String hexToTronBase58(String hexAddress) {
        if (hexAddress == null || hexAddress.isBlank()) return hexAddress;
        // 已经是 Base58 格式（T 开头）则直接返回
        if (hexAddress.startsWith("T")) return hexAddress;
        try {
            String hex = hexAddress.startsWith("0x") ? hexAddress.substring(2) : hexAddress;
            byte[] rawAddress = hexStringToBytes("41" + hex);
            // SHA-256 双重哈希取前 4 字节作为校验码
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash1 = sha256.digest(rawAddress);
            byte[] hash2 = sha256.digest(hash1);
            byte[] addressWithChecksum = new byte[rawAddress.length + 4];
            System.arraycopy(rawAddress, 0, addressWithChecksum, 0, rawAddress.length);
            System.arraycopy(hash2, 0, addressWithChecksum, rawAddress.length, 4);
            return base58Encode(addressWithChecksum);
        } catch (Exception e) {
            log.warn("Failed to convert hex to Tron Base58: error={}", e.getClass().getSimpleName());
            return hexAddress;
        }
    }

    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private String base58Encode(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        BigInteger base = BigInteger.valueOf(58);
        StringBuilder sb = new StringBuilder();
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = value.divideAndRemainder(base);
            value = divmod[0];
            sb.append(BASE58_ALPHABET.charAt(divmod[1].intValue()));
        }
        // 前导零字节对应 Base58 的 '1'
        for (byte b : input) {
            if (b == 0) sb.append('1');
            else break;
        }
        return sb.reverse().toString();
    }
}
