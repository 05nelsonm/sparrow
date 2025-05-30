package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.Transport;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcBatchException;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletHistoryStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.sparrowwallet.drongo.wallet.WalletNode.nodeRangesToString;

public class BatchedElectrumServerRpc implements ElectrumServerRpc {
    private static final Logger log = LoggerFactory.getLogger(BatchedElectrumServerRpc.class);
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int RETRY_DELAY_SECS = 1;

    private final AtomicLong idCounter;
    private final int maxTargetBlocks;

    public BatchedElectrumServerRpc(long idCounterValue, int maxTargetBlocks) {
        this.idCounter = new AtomicLong(idCounterValue);
        this.maxTargetBlocks = maxTargetBlocks;
    }

    @Override
    public void ping(Transport transport) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            new RetryLogic<>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().method("server.ping").id(idCounter.incrementAndGet()).executeNullable());
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error pinging server", e);
        }
    }

    @Override
    public List<String> getServerVersion(Transport transport, String clientName, String[] supportedVersions) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            return new RetryLogic<List<String>>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAsList(String.class).method("server.version").id(idCounter.incrementAndGet()).param("client_name", clientName).param("protocol_version", supportedVersions).execute());
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error getting server version", e);
        }
    }

    @Override
    public String getServerBanner(Transport transport) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            return new RetryLogic<String>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAs(String.class).method("server.banner").id(idCounter.incrementAndGet()).execute());
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error getting server banner", e);
        }
    }

    @Override
    public BlockHeaderTip subscribeBlockHeaders(Transport transport) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            return new RetryLogic<BlockHeaderTip>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAs(BlockHeaderTip.class).method("blockchain.headers.subscribe").id(idCounter.incrementAndGet()).execute());
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error subscribing to block headers", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, ScriptHashTx[]> getScriptHashHistory(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError) {
        PagedBatchRequestBuilder<String, ScriptHashTx[]> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(ScriptHashTx[].class);
        EventManager.get().post(new WalletHistoryStatusEvent(wallet, true, "Loading transactions for " + nodeRangesToString(pathScriptHashes.keySet())));

        for(String path : pathScriptHashes.keySet()) {
            batchRequest.add(path, "blockchain.scripthash.get_history", pathScriptHashes.get(path));
        }

        try {
            return batchRequest.execute();
        } catch (JsonRpcBatchException e) {
            if(failOnError) {
                throw new ElectrumServerRpcException("Failed to retrieve transaction history for paths: " + nodeRangesToString((Collection<String>)e.getErrors().keySet()), e);
            }

            Map<String, ScriptHashTx[]> result = (Map<String, ScriptHashTx[]>)e.getSuccesses();
            for(Object key : e.getErrors().keySet()) {
                result.put((String)key, new ScriptHashTx[] {ScriptHashTx.ERROR_TX});
            }

            return result;
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve transaction history for paths: " + nodeRangesToString(pathScriptHashes.keySet()), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, ScriptHashTx[]> getScriptHashMempool(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError) {
        PagedBatchRequestBuilder<String, ScriptHashTx[]> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(ScriptHashTx[].class);

        for(String path : pathScriptHashes.keySet()) {
            batchRequest.add(path, "blockchain.scripthash.get_mempool", pathScriptHashes.get(path));
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            if(failOnError) {
                throw new ElectrumServerRpcException("Failed to retrieve mempool transactions for paths: " + nodeRangesToString((Collection<String>)e.getErrors().keySet()), e);
            }

            Map<String, ScriptHashTx[]> result = (Map<String, ScriptHashTx[]>)e.getSuccesses();
            for(Object key : e.getErrors().keySet()) {
                result.put((String)key, new ScriptHashTx[] {ScriptHashTx.ERROR_TX});
            }

            return result;
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve mempool transactions for paths: " + nodeRangesToString(pathScriptHashes.keySet()), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> subscribeScriptHashes(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes) {
        PagedBatchRequestBuilder<String, String> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(String.class);
        EventManager.get().post(new WalletHistoryStatusEvent(wallet, true, "Finding transactions for " + nodeRangesToString(pathScriptHashes.keySet())));

        for(String path : pathScriptHashes.keySet()) {
            batchRequest.add(path, "blockchain.scripthash.subscribe", pathScriptHashes.get(path));
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            //Even if we have some successes, failure to subscribe for all script hashes will result in outdated wallet view. Don't proceed.
            throw new ElectrumServerRpcException("Failed to subscribe to paths: " + nodeRangesToString((Collection<String>)e.getErrors().keySet()), e);
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to subscribe to paths: " + nodeRangesToString(pathScriptHashes.keySet()), e);
        }
    }

    @Override
    public Map<String, Boolean> unsubscribeScriptHashes(Transport transport, Set<String> scriptHashes) {
        PagedBatchRequestBuilder<String, Boolean> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(Boolean.class);

        for(String scriptHash : scriptHashes) {
            batchRequest.add(scriptHash, "blockchain.scripthash.unsubscribe", scriptHash);
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            log.info("Failed to unsubscribe from script hashes: " + e.getErrors().keySet(), e);
            Map<String, Boolean> unsubscribedScriptHashes = scriptHashes.stream().collect(Collectors.toMap(s -> s, _ -> true));
            unsubscribedScriptHashes.keySet().removeIf(scriptHash -> e.getErrors().containsKey(scriptHash));
            return unsubscribedScriptHashes;
        } catch(Exception e) {
            log.info("Failed to unsubscribe from script hashes: " + scriptHashes, e);
            return Collections.emptyMap();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Integer, String> getBlockHeaders(Transport transport, Wallet wallet, Set<Integer> blockHeights) {
        PagedBatchRequestBuilder<Integer, String> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(Integer.class).returnType(String.class);
        EventManager.get().post(new WalletHistoryStatusEvent(wallet, true, "Retrieving " + blockHeights.size() + " block headers"));

        for(Integer height : blockHeights) {
            batchRequest.add(height, "blockchain.block.header", height);
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            return (Map<Integer, String>)e.getSuccesses();
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve block headers for block heights: " + blockHeights, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Integer, BlockStats> getBlockStats(Transport transport, Set<Integer> blockHeights) {
        PagedBatchRequestBuilder<Integer, BlockStats> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(Integer.class).returnType(BlockStats.class);

        for(Integer height : blockHeights) {
            batchRequest.add(height, "blockchain.block.stats", height);
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            return (Map<Integer, BlockStats>)e.getSuccesses();
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve block stats for block heights: " + blockHeights, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
        PagedBatchRequestBuilder<String, String> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(String.class);
        EventManager.get().post(new WalletHistoryStatusEvent(wallet, true, "Retrieving " + txids.size() + " transactions"));

        for(String txid : txids) {
            batchRequest.add(txid, "blockchain.transaction.get", txid);
        }

        try {
            return batchRequest.execute();
        } catch(JsonRpcBatchException e) {
            Map<String, String> result = (Map<String, String>)e.getSuccesses();

            String strErrorTx = Sha256Hash.ZERO_HASH.toString();
            for(Object hash : e.getErrors().keySet()) {
                String txhash = (String)hash;
                result.put(txhash, strErrorTx);
            }

            return result;
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve transactions for txids: " + txids.stream().map(txid -> "[" + txid.substring(0, 6) + "]").collect(Collectors.toList()), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, VerboseTransaction> getVerboseTransactions(Transport transport, Set<String> txids, String scriptHash) {
        PagedBatchRequestBuilder<String, VerboseTransaction> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(String.class).returnType(VerboseTransaction.class);
        for(String txid : txids) {
            batchRequest.add(txid, "blockchain.transaction.get", txid, true);
        }

        try {
            //The server may return an error if the transaction has not yet been broadcasted - this is a valid state so only try once
            return batchRequest.execute(1);
        } catch(JsonRpcBatchException e) {
            log.debug("Some errors retrieving transactions: " + e.getErrors());
            return (Map<String, VerboseTransaction>)e.getSuccesses();
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Failed to retrieve verbose transactions for txids: " + txids, e);
        }
    }

    @Override
    public Map<Integer, Double> getFeeEstimates(Transport transport, List<Integer> targetBlocks) {
        PagedBatchRequestBuilder<Integer, Double> batchRequest = PagedBatchRequestBuilder.create(transport, idCounter).keysType(Integer.class).returnType(Double.class);
        for(Integer targetBlock : targetBlocks) {
            if(targetBlock <= maxTargetBlocks) {
                batchRequest.add(targetBlock, "blockchain.estimatefee", targetBlock);
            }
        }

        try {
            Map<Integer, Double> result = batchRequest.execute();
            for(Integer targetBlock : targetBlocks) {
                if(targetBlock > maxTargetBlocks) {
                    result.put(targetBlock, result.values().stream().mapToDouble(v -> v).min().orElse(0.0001d));
                }
            }
            return result;
        } catch(JsonRpcBatchException e) {
            throw new ElectrumServerRpcException("Error getting fee estimates from connected server: " + e.getErrors(), e);
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error getting fee estimates for target blocks: " + targetBlocks, e);
        }
    }

    @Override
    public Map<Double, Long> getFeeRateHistogram(Transport transport) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            BigDecimal[][] feesArray = new RetryLogic<BigDecimal[][]>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAs(BigDecimal[][].class).method("mempool.get_fee_histogram").id(idCounter.incrementAndGet()).execute());

            Map<Double, Long> feeRateHistogram = new TreeMap<>();
            for(BigDecimal[] feePair : feesArray) {
                if(feePair[0].longValue() > 0) {
                    feeRateHistogram.put(feePair[0].doubleValue(), feePair[1].longValue());
                }
            }

            return feeRateHistogram;
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error getting fee rate histogram", e);
        }
    }

    @Override
    public Double getMinimumRelayFee(Transport transport) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            return new RetryLogic<Double>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAs(Double.class).method("blockchain.relayfee").id(idCounter.incrementAndGet()).execute());
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error getting minimum relay fee", e);
        }
    }

    @Override
    public String broadcastTransaction(Transport transport, String txHex) {
        try {
            JsonRpcClient client = new JsonRpcClient(transport);
            return new RetryLogic<String>(DEFAULT_MAX_ATTEMPTS, RETRY_DELAY_SECS, IllegalStateException.class).getResult(() ->
                    client.createRequest().returnAs(String.class).method("blockchain.transaction.broadcast").id(idCounter.incrementAndGet()).params(txHex).execute());
        } catch(JsonRpcException e) {
            throw new ElectrumServerRpcException(e.getErrorMessage().getMessage(), e);
        } catch(Exception e) {
            throw new ElectrumServerRpcException("Error broadcasting transaction", e);
        }
    }

    @Override
    public long getIdCounterValue() {
        return idCounter.get();
    }
}
