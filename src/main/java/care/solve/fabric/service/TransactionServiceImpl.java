package care.solve.fabric.service;

import com.google.protobuf.ByteString;
import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class TransactionServiceImpl implements TransactionService {

    private HFClientFactory hfClientFactory;
    private ChaincodeID chaincodeId;
    private Channel healthChannel;

    @Autowired
    public TransactionServiceImpl(HFClientFactory hfClientFactory, ChaincodeID chaincodeId, Channel healthChannel) {
        this.hfClientFactory = hfClientFactory;
        this.chaincodeId = chaincodeId;
        this.healthChannel = healthChannel;
    }

    public BlockEvent.TransactionEvent sendInvokeTransaction(String func, String[] args) {
        try {
            HFClient client = hfClientFactory.getClient();

            TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
            transactionProposalRequest.setChaincodeID(chaincodeId);
            transactionProposalRequest.setFcn(func);
            transactionProposalRequest.setProposalWaitTime(20000L);
            transactionProposalRequest.setArgs(args);

            Collection<ProposalResponse> transactionPropResp = healthChannel.sendTransactionProposal(transactionProposalRequest, healthChannel.getPeers());
            long failedResponsesCount = transactionPropResp.stream().filter(resp -> !resp.getStatus().equals(ProposalResponse.Status.SUCCESS)).count();
            if (failedResponsesCount != 0) {
                throw new RuntimeException(String.format("Failed transaction: %d failed from %d ", failedResponsesCount, transactionPropResp.size()));
            }

            CompletableFuture<BlockEvent.TransactionEvent> proposalResponce = healthChannel.sendTransaction(transactionPropResp, client.getUserContext());

            return proposalResponce.get();
        } catch (InvalidArgumentException | ProposalException | InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public ByteString sendQueryTransaction(String func, String[] args) {
        try {
            QueryByChaincodeRequest queryByChaincodeRequest = hfClientFactory.getClient().newQueryProposalRequest();
            queryByChaincodeRequest.setFcn(func);
            queryByChaincodeRequest.setArgs(args);
            queryByChaincodeRequest.setChaincodeID(chaincodeId);

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
            queryByChaincodeRequest.setTransientMap(tm2);

            Collection<ProposalResponse> queryProposals = healthChannel.queryByChaincode(queryByChaincodeRequest, healthChannel.getPeers());
            for (ProposalResponse proposalResponse : queryProposals) {
                if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                    System.out.println("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                            ". Messages: " + proposalResponse.getMessage()
                            + ". Was verified : " + proposalResponse.isVerified());
                } else {
                    String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                    System.out.println(String.format("Query payload from peer %s returned %s", proposalResponse.getPeer().getName(), payload));

                    return proposalResponse.getProposalResponse().getResponse().getPayload();
                }
            }
        } catch (InvalidArgumentException | ProposalException e) {
            e.printStackTrace();
        }

        return null;
    }

}