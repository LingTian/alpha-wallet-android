package io.stormbird.token.web.Ethereum;

import io.stormbird.token.entity.MagicLinkInfo;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.stormbird.token.entity.BadContract;
import io.stormbird.token.entity.MagicLinkData;
import okhttp3.OkHttpClient;

public class TransactionHandler
{
    private static Web3j mWeb3;
    private static int web3NetworkId;

    public TransactionHandler(int networkId)
    {
        setupNetwork(networkId);
    }

    private void setupNetwork(int networkId)
    {
        String nodeURL = MagicLinkInfo.getNodeURLByNetworkId(networkId);
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(20, TimeUnit.SECONDS);
        builder.readTimeout(20, TimeUnit.SECONDS);
        HttpService service = new HttpService(nodeURL, builder.build(), false);
        mWeb3 = Web3j.build(service);
        web3NetworkId = networkId;
        try
        {
            Web3ClientVersion web3ClientVersion = mWeb3.web3ClientVersion().sendAsync().get();
            System.out.println(web3ClientVersion.getWeb3ClientVersion());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public List<BigInteger> getBalanceArray(String address, String contractAddress) throws Exception
    {
        List<BigInteger> result = new ArrayList<>();
        org.web3j.abi.datatypes.Function function = balanceOfArray(address);
        List<Uint256> indices = callSmartContractFunctionArray(function, contractAddress, address);
        if (indices == null) throw new BadContract();
        for (Uint256 val : indices)
        {
            result.add(val.getValue());
        }
        return result;
    }

    public boolean checkBalance(String address, int chainId, String contractAddress)
    {
        if (web3NetworkId != chainId)
        {
            setupNetwork(chainId);
        }

        try
        {
            org.web3j.abi.datatypes.Function function = balanceOfArray(address);
            List<Uint256> balance = callSmartContractFunctionArray(function, contractAddress, address);
            for (Uint256 id : balance)
            {
                if (!id.equals(BigInteger.ZERO))
                {
                    return true;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return false;
    }

    public String getName(String address)
    {
        String name = "";
        String symbol = "";
        try
        {
            name = getContractData(address, stringParam("name"));
            symbol = getContractData(address, stringParam("symbol"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return name + " (" + symbol + ")";
    }

    private <T> T getContractData(String address, org.web3j.abi.datatypes.Function function) throws Exception
    {
        String responseValue = callSmartContractFunction(function, address);

        List<Type> response = FunctionReturnDecoder.decode(
                responseValue, function.getOutputParameters());
        if (response.size() == 1)
        {
            return (T) response.get(0).getValue();
        }
        else
        {
            return null;
        }
    }

    private String callSmartContractFunction(
            Function function, String contractAddress) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);
        return makeEthCall(
                org.web3j.protocol.core.methods.request.Transaction
                        .createEthCallTransaction(null, contractAddress, encodedFunction));
    }

    private String makeEthCall(Transaction transaction) throws ExecutionException, InterruptedException
    {
        org.web3j.protocol.core.methods.response.EthCall ethCall = mWeb3.ethCall(transaction,
                DefaultBlockParameterName.LATEST)
                .sendAsync().get();
        return ethCall.getValue();
    }

    private List callSmartContractFunctionArray(
            org.web3j.abi.datatypes.Function function, String contractAddress, String address) throws Exception
    {
        String encodedFunction = FunctionEncoder.encode(function);
        String value = makeEthCall(
                org.web3j.protocol.core.methods.request.Transaction
                        .createEthCallTransaction(address, contractAddress, encodedFunction));

        List<Type> values = FunctionReturnDecoder.decode(value, function.getOutputParameters());
        if (values.isEmpty()) return null;

        Type T = values.get(0);
        Object o = T.getValue();
        return (List) o;
    }

    private static org.web3j.abi.datatypes.Function stringParam(String param) {
        return new Function(param,
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Utf8String>() {
                }));
    }

    private static org.web3j.abi.datatypes.Function balanceOfArray(String owner) {
        return new org.web3j.abi.datatypes.Function(
                "balanceOf",
                Collections.singletonList(new Address(owner)),
                Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {}));
    }

    public boolean checkBalances(String address, int chainId, String contractAddress)
    {
        //checkBalance(String address, int chainId, String contractAddress)
        //try all contracts in chain
        boolean hasToken = checkBalance(address, chainId, contractAddress);

        if (!hasToken) hasToken = checkBalance(address, 1, "0x63cCEF733a093E5Bd773b41C96D3eCE361464942");
        if (!hasToken) hasToken = checkBalance(address, 3, "0xFB82A5a2922A249f32222316b9D1F5cbD3838678");
        if (!hasToken) hasToken = checkBalance(address, 4, "0x7c81DF31BB2f54f03A56Ab25c952bF3Fa39bDF46");
        if (!hasToken) hasToken = checkBalance(address, 42, "0x2B58A9403396463404c2e397DBF37c5EcCAb43e5");

        return hasToken;
    }
}
