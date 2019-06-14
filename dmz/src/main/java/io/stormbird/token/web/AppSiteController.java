package io.stormbird.token.web;

import io.stormbird.token.entity.*;
import io.stormbird.token.tools.Numeric;
import io.stormbird.token.tools.TSValidator;
import io.stormbird.token.tools.TokenDefinition;
import io.stormbird.token.util.DateTimeFactory;
import org.apache.commons.io.IOUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.stormbird.token.tools.ParseMagicLink;
import io.stormbird.token.web.Ethereum.TransactionHandler;
import io.stormbird.token.web.Service.CryptoFunctions;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import org.w3c.dom.Document;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.xml.sax.SAXException;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static io.stormbird.token.tools.Convert.getEthString;
import static io.stormbird.token.tools.Convert.getEthStringSzabo;
import static io.stormbird.token.tools.ParseMagicLink.currencyLink;
import static io.stormbird.token.tools.ParseMagicLink.spawnable;


@CrossOrigin(origins= {"*"}, maxAge = 4800, allowCredentials = "false" )
@Controller
@SpringBootApplication
@RequestMapping("/")
public class AppSiteController {

    private static CryptoFunctions cryptoFunctions = new CryptoFunctions();
    private static Map<String, File> addresses;
    private static Map<String, String> signatures;
    private static String lastChallenge = "";
    private static long challengeTime = 0;
    private static long doorOpenTime = 0;
    private static String statusString = "Door Closed";
    private static long statusTime = 0;

    private static final String appleAssociationConfig = "{\n" +
            "  \"applinks\": {\n" +
            "    \"apps\": [],\n" +
            "    \"details\": [\n" +
            "      {\n" +
            "        \"appID\": \"LRAW5PL536.com.stormbird.alphawallet\",\n" +
            "        \"paths\": [ \"*\" ]\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";
    private final MagicLinkData magicLinkData = new MagicLinkData();
    private TransactionHandler queryTxHandler;

    private static final String EMPTY = "EMPTY";
    public static final String PERSONAL_MESSAGE_PREFIX = "\u0019Ethereum Signed Message:\n";

    @GetMapping(value = "/apple-app-site-association", produces = "application/json")
    @ResponseBody
    public String getAppleDeepLinkConfigure() {
        return appleAssociationConfig;
    }

//    @GetMapping("/")
//    public RedirectView home(RedirectAttributes attributes){
//        return new RedirectView("http://alphawallet.com");
//    }

    @GetMapping(value = "/{UniversalLink}")
    public String handleUniversalLink(
            @PathVariable("UniversalLink") String universalLink,
            @RequestHeader("User-Agent") String agent,
            Model model,
            HttpServletRequest request
    )
            throws IOException, SAXException, NoHandlerFoundException
    {
        String domain = request.getServerName();
        ParseMagicLink parser = new ParseMagicLink(new CryptoFunctions());
        MagicLinkData data;
        model.addAttribute("base64", universalLink);
        try
        {
            data = parser.parseUniversalLink(universalLink);
            data.chainId = MagicLinkInfo.getNetworkIdFromDomain(domain);
        }
        catch (SalesOrderMalformed e)
        {
            return "error: " + e;
        }
        parser.getOwnerKey(data);
        switch (data.contractType)
        {
            case currencyLink:
                return handleCurrencyLink(data, agent, model);
            case spawnable:
                return handleSpawnableLink(data, agent, model);
            default:
                return handleTokenLink(data, agent, model);
        }
    }

    private String handleTokenLink(
            MagicLinkData data,
            String agent,
            Model model
    ) throws IOException, SAXException, NoHandlerFoundException
    {
        TokenDefinition definition = getTokenDefinition(data.contractAddress);

        model.addAttribute("tokenName", definition.getTokenName());
        model.addAttribute("link", data);
        model.addAttribute("linkPrice", getEthString(data.price));

        try {
            updateContractInfo(model, data);
        } catch (Exception e) {
            /* The link points to a non-existing contract - most
	     * likely from a different chainID. Now, if Ethereum node
	     * is offline, this may get triggered too. */
            model.addAttribute("tokenAvailable", "unattainable");
            return "index";
        }

        try {
            updateTokenInfo(model, data, definition);
        } catch (Exception e) {
            /* although contract is okay, we can't getting
	     * tokens. This could be caused by a wrong signature. The
	     * case that the tokens are redeemd is handled inside, not
	     * as an exception */
            model.addAttribute("tokenAvailable", "unavailable");
            return "index";
        }

        if (Calendar.getInstance().getTime().after(new Date(data.expiry*1000))){
            model.addAttribute("tokenAvailable", "expired");
        } else {
            model.addAttribute("tokenAvailable", "available");
        }
        return "index";
    }

    private String handleCurrencyLink(
            MagicLinkData data,
            String agent,
            Model model
    ) throws IOException, SAXException, NoHandlerFoundException
    {
        String networkName = MagicLinkInfo.getNetworkNameById(data.chainId);
        model.addAttribute("link", data);
        model.addAttribute("linkValue", getEthStringSzabo(data.amount));
        model.addAttribute("title", networkName + " Currency Drop");
        model.addAttribute("currency", networkName);
        model.addAttribute("domain", MagicLinkInfo.getMagicLinkDomainFromNetworkId(data.chainId));

        try {
            updateContractInfo(model, data);
        } catch (Exception e) {
            /* The link points to a non-existing contract - most
             * likely from a different chainID. Now, if Ethereum node
             * is offline, this may get triggered too. */
            model.addAttribute("tokenAvailable", "unattainable");
            return "currency";
        }

        if (Calendar.getInstance().getTime().after(new Date(data.expiry*1000))){
            model.addAttribute("tokenAvailable", "expired");
        } else {
            model.addAttribute("tokenAvailable", "available");
        }
        return "currency";
    }

    private void checkTokenDefinition(String address)
    {
//        try(FileInputStream in = new FileInputStream(xml)) {
//            // TODO: give more detail in the error
//            // TODO: reflect on this: should the page bail out for contracts with completely no matching XML?
//            definition = new TokenDefinition(in, new Locale("en"), null);
//        }
    }

    private String handleSpawnableLink(
            MagicLinkData data,
            String agent,
            Model model
    ) throws IOException, SAXException, NoHandlerFoundException
    {
        TokenDefinition definition = getTokenDefinition(data.contractAddress);

        String tokenName = definition.getTokenName();

        model.addAttribute("tokenName", definition.getTokenName());
        model.addAttribute("link", data);
        model.addAttribute("linkPrice", getEthString(data.price));
        model.addAttribute("domain", MagicLinkInfo.getMagicLinkDomainFromNetworkId(data.chainId));

        try {
            updateContractInfo(model, data);
        } catch (Exception e) {
            /* The link points to a non-existing contract - most
             * likely from a different chainID. Now, if Ethereum node
             * is offline, this may get triggered too. */
            model.addAttribute("tokenAvailable", "unattainable");
            return "spawnable";
        }

        try {
            updateTokenInfoForSpawnable(model, data, definition);
        } catch (Exception e) {
            /* although contract is okay, we can't getting
             * tokens. This could be caused by a wrong signature. The
             * case that the tokens are redeemd is handled inside, not
             * as an exception */
            model.addAttribute("tokenAvailable", "unavailable");
            return "spawnable";
        }

        if (Calendar.getInstance().getTime().after(new Date(data.expiry*1000))){
            model.addAttribute("tokenAvailable", "expired");
        } else {
            model.addAttribute("tokenAvailable", "available");
        }
        return "spawnable";
    }

    private TokenDefinition getTokenDefinition(String contractAddress) throws IOException, SAXException, NoHandlerFoundException
    {
        File xml = null;
        TokenDefinition definition;
        for (String address : addresses.keySet()) {
            xml = addresses.get(address);
            // TODO: when xml-schema-v1 is merged, produce a new "default XML" to fill the role of fallback.
            if (address.equals(contractAddress)) { // this works as contractAddress is always in lowercase
                break;
            }
        }
        if (xml == null) {
            /* this is impossible to happen, because at least 1 xml should present or main() bails out */
            throw new NoHandlerFoundException("GET", "/" + contractAddress, new HttpHeaders());
        }
        try(FileInputStream in = new FileInputStream(xml)) {
            // TODO: give more detail in the error
            // TODO: reflect on this: should the page bail out for contracts with completely no matching XML?
            definition = new TokenDefinition(in, new Locale("en"), null);
        }

        return definition;
    }

    private void updateContractInfo(Model model, MagicLinkData data) {
        //find out the contract name, symbol and balance
        //have to use blocking gets here
        //TODO: we should be able to update components here instead of waiting
        TransactionHandler txHandler = new TransactionHandler(data.chainId);
        String contractName = txHandler.getName(data.contractAddress);
        model.addAttribute("contractName", contractName);
    }

    private void updateTokenInfoForSpawnable(Model model, MagicLinkData data, TokenDefinition definition) throws Exception {
        // TODO: use the locale negotiated with user agent (content-negotiation) instead of English
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH);

        List<NonFungibleToken> selection = Arrays.stream(data.tokenIds.toArray(new BigInteger[0]))
                .filter(tokenId -> !tokenId.equals(BigInteger.ZERO))
                .map(tokenId -> new NonFungibleToken(tokenId, definition))
                .collect(Collectors.toList());

        for (NonFungibleToken token : selection)
        {
            int index = 1;
            for (String key : token.getAttributes().keySet())
            {
                String def = "attr" + index;
                String val = "val" + index;
                NonFungibleToken.Attribute attr = token.getAttribute(key);
                switch (key)
                {
                    case "time":
                        model.addAttribute("ticketDate", DateTimeFactory.getDateTime(attr).format(dateFormat));
                        break;

                    case "numero":
                        model.addAttribute(key, attr.text);
                        break;

                    default:
                        model.addAttribute(def, attr.name);
                        model.addAttribute(val, attr.text);
                        index++;
                        break;
                }
            }

            for (;index < 3;index++)
            {
                model.addAttribute("attr"+index, "");
                model.addAttribute("val"+index, "");
            }

            //prevent page from failing if attribute didn't contain numero
            if (!model.containsAttribute("numero"))
            {
                model.addAttribute("numero", "1");
            }

            break; // we only need 1 token's info. rest assumed to be the same
        }
    }

    private void updateTokenInfo(
            Model model,
            MagicLinkData data,
            TokenDefinition definition
    ) throws Exception {
        model.addAttribute("domain", MagicLinkInfo.getMagicLinkDomainFromNetworkId(data.chainId));
        TransactionHandler txHandler = new TransactionHandler(data.chainId);
        // TODO: use the locale negotiated with user agent (content-negotiation) instead of English
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH);
        List<BigInteger> balanceArray = txHandler.getBalanceArray(data.ownerAddress, data.contractAddress);

        List<NonFungibleToken> selection = Arrays.stream(data.tickets)
                .mapToObj(i -> balanceArray.get(i))
                .filter(tokenId -> !tokenId.equals(BigInteger.ZERO))
                .map(tokenId -> new NonFungibleToken(tokenId, definition))
                .collect(Collectors.toList());

        for (NonFungibleToken token : selection) {
            if (token.getAttribute("countryA") != null)
            {
                String sides = token.getAttribute("countryA").text;
                sides += " - " + token.getAttribute("countryB").text;
                model.addAttribute("ticketSides", sides);
                model.addAttribute("ticketDate",
                                   DateTimeFactory.getDateTime(token.getAttribute("time")).format(dateFormat));
                model.addAttribute("ticketMatch", token.getAttribute("match").text);
                model.addAttribute("ticketCategory", token.getAttribute("category").text);
            }
            break; // we only need 1 token's info. rest assumed to be the same
        }

        if (selection.size() != data.tickets.length)
            throw new Exception("Some or all non-fungiable tokens are not owned by the claimed owner");
    }

    private static Path repoDir;

    @Value("${repository.dir}")
    public void setRepoDir(String value) {
        repoDir = Paths.get(value);
    }

    public static void main(String[] args) throws IOException { // TODO: should run System.exit() if IOException
        SpringApplication.run(AppSiteController.class, args);
        signatures = new ConcurrentHashMap<>();
        try (Stream<Path> dirStream = Files.walk(repoDir)) {
            addresses = dirStream.filter(path -> path.toString().toLowerCase().endsWith(".xml"))
                    .filter(Files::isRegularFile)
                    .filter(Files::isReadable)
                    .map(path -> getContractAddresses(path))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().toLowerCase(),
                            entry -> new File(entry.getValue())));
            assert addresses != null : "Can't read all XML files";
        } catch (NoSuchFileException e) {
            System.err.println("repository.dir property is defined with a non-existing dir: " + repoDir.toString());
            System.err.println("Please edit your local copy of application.properties, or");
            System.err.println("try run with --repository.dir=/dir/to/repo");
            System.exit(255);
        } catch (AssertionError e) {
            System.err.println("Can't read all the XML files in repository.dir: " + repoDir.toString());
            System.exit(254);
        }

        if (addresses.size() == 0) { // if no XML file is found
            // the server still can run and wait for someone to dump an XML, but let's assume it's a mistake
            System.err.println("No valid contract XML found in " + repoDir.toString() + ", cowardly not continuing.");
            System.exit(253);
        } else {
            // the list should be reprinted whenever a new file is added.
            System.out.println("Serving an XML repo with the following contracts:");
            addresses.forEach((addr, xml) -> System.out.println(addr + ":" + xml.getPath()));
        }
	}

	private static Set<Map.Entry<String, String>> getContractAddresses(Path path) {
        HashMap<String, String> map = new HashMap<>();
        try (InputStream input = Files.newInputStream(path)) {
            TokenDefinition token = new TokenDefinition(input, new Locale("en"), null);
            token.addresses.keySet().stream().forEach(address -> map.put(address, path.toString()));
            return map.entrySet();
        } catch (IOException | SAXException e) {
            throw new RuntimeException(e); // make it safe to use in stream
        }
    }

    @GetMapping(value = "/0x{address}", produces = MediaType.TEXT_XML_VALUE) // TODO: use regexp 0x[0-9a-fA-F]{20}
    public @ResponseBody String getContractBehaviour(@PathVariable("address") String address)
            throws IOException, NoHandlerFoundException
    {
        /* TODO: should parse the address, do checksum, store in a byte160 */
        address = "0x" + address.toLowerCase();
        if (addresses.containsKey(address)) {
            File file = addresses.get(address);
            try (FileInputStream in = new FileInputStream(file)) {
                /* TODO: check XML's encoding and serve a charset according to the encoding */
                return IOUtils.toString(in, "utf8");
            }
        } else {
            throw new NoHandlerFoundException("GET", "/" + address, new HttpHeaders());
        }
    }

    @PostMapping("/api/checkSig")
    @ResponseBody
    public ResponseEntity<String> handleFileUpload(@RequestParam("file") MultipartFile file,
                                                   RedirectAttributes redirectAttributes)
    {
        JSONArray holder = new JSONArray();

        HttpStatus status = HttpStatus.ACCEPTED;
        JSONObject result = new JSONObject();

        try
        {
            SignatureCheck sigCheck = checkSignature(file);
            if (sigCheck.isValid)
            {
                result.put("result", "pass");
                result.put("issuer", sigCheck.issuerPrincipal);
                result.put("subject", sigCheck.subjectPrincipal);
                result.put("keyName", sigCheck.keyName);
                result.put("keyType", sigCheck.keyType);
            }
            else
            {
                result.put("result", "fail");
            }

            holder.add(result);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            status = HttpStatus.BAD_REQUEST;
        }

        return new ResponseEntity<String>(result.toString(), status);
    }

    private SignatureCheck checkSignature(MultipartFile file)
    {
        SignatureCheck result = new SignatureCheck();
        if (file.isEmpty())
        {
            return result;
        }

        try
        {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document xml = dBuilder.parse(file.getInputStream());
            xml.getDocumentElement().normalize();
            result = TSValidator.check(xml);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return result;
    }

    @GetMapping(value = "/")
    public String doorSite(Model model, HttpServletRequest request)
    {
        model.addAttribute("title", "TokenScript Door Challenge Simulator");
        model.addAttribute("challenge", lastChallenge);
        String doorGraphic = "images/closedDoor.jpg";
        if (doorOpenTime > 0)
        {
            if ((System.currentTimeMillis() - doorOpenTime) > 1000 * 15)
            {
                doorOpenTime = 0;
                statusString = "Door Closed";
            }
            else
            {
                doorGraphic = "images/front door open.jpg";
            }
        }
        model.addAttribute("image", doorGraphic);
        model.addAttribute("status", statusString);
        return "doorchallenge";
    }

    private static final String[] seedWords = { "Apples", "Oranges", "Grapes", "Dragon fruit", "Bread fruit", "Pomegranate", "Mangifera indica", "Persea americana", "Falafel" };

    @RequestMapping(value = "/api/getChallenge", method = RequestMethod.GET)
    public ResponseEntity getChallenge(

    )
    {
        byte[] bytes = new byte[256];
        SecureRandom random = new SecureRandom();
        random.nextBytes(bytes);
        BigInteger bi = new BigInteger(bytes);
        String addend = bi.toString(16).substring(0,9);
        random.nextBytes(bytes);
        bi = new BigInteger(bytes);
        int index = bi.mod(BigInteger.valueOf(seedWords.length)).intValue();
        String challenge = seedWords[index] + "-" + addend;
        signatures.put(challenge, EMPTY);
        lastChallenge = challenge;
        challengeTime = System.currentTimeMillis();
        statusString = "Issue Challenge";
        return new ResponseEntity<>(challenge, HttpStatus.ACCEPTED);
    }

    @RequestMapping(value = "/api/checkSignature", method = RequestMethod.GET)
    public ResponseEntity checkSignature(
            @RequestParam(value="contract") String contractAddress,
            @RequestParam(value="challenge") String challenge,
            @RequestParam(value="sig") String signature)
    {
        HttpStatus callStatus = HttpStatus.ACCEPTED;
        String result = "fail";
        System.out.println("Checking: " + challenge + " @" + contractAddress);
        lastChallenge = "";
        //first check this is a valid challenge
        String check = signatures.get(challenge);
        if (check != null && check.equals(EMPTY))
        {
            //check signature
            String address = ecRecoverPersonal(challenge, signature);
            System.out.println("Recovered: " + address);
            if (queryTxHandler == null) queryTxHandler = new TransactionHandler(3);
            //check this address for a token
            if (queryTxHandler.checkBalances(address, 3, contractAddress))
            {
                System.out.println("has token");
                result = "pass";
                signatures.put(challenge, signature);
                callStatus = HttpStatus.ACCEPTED;
                statusString = "Door opening ...";
                doorOpenTime = System.currentTimeMillis();
            }
            else
            {
                System.out.println("doesn't have any tokens");
                result = "no token";
                statusString = "Failed Token Check";
            }
        }

        statusTime = System.currentTimeMillis();

        return new ResponseEntity<>(result, callStatus);
    }

    public String ecRecover(String challenge, String signature)
    {
        //reconstruct Signature from hex
        Sign.SignatureData sigData = sigFromByteArray(Numeric.hexStringToByteArray(signature));
        String recoveredAddr = "0x";

        try
        {
            BigInteger recoveredKey = Sign.signedMessageToKey(challenge.getBytes(), sigData);
            recoveredAddr = "0x" + Keys.getAddress(recoveredKey);
            System.out.println("Recovered: " + recoveredAddr);
        }
        catch (SignatureException e)
        {
            e.printStackTrace();
        }
        return recoveredAddr;
    }

    public static Sign.SignatureData sigFromByteArray(byte[] sig)
    {
        byte subv = sig[64];
        if (subv < 27) subv += 27;

        byte[] subrRev = Arrays.copyOfRange(sig, 0, 32);
        byte[] subsRev = Arrays.copyOfRange(sig, 32, 64);

        BigInteger r = new BigInteger(1, subrRev);
        BigInteger s = new BigInteger(1, subsRev);

        return new Sign.SignatureData(subv, subrRev, subsRev);
    }

    public String ecRecoverPersonal(String message, String sig)
    {
        String prefix = PERSONAL_MESSAGE_PREFIX + message.length();
        byte[] msgHash = (prefix + message).getBytes();

        byte[] signatureBytes = Numeric.hexStringToByteArray(sig);
        Sign.SignatureData sd = sigFromByteArray(signatureBytes);
        String addressRecovered = "";

        try
        {
            BigInteger recoveredKey = Sign.signedMessageToKey(msgHash, sd);
            addressRecovered = "0x" + Keys.getAddress(recoveredKey);
            System.out.println("Recovered: " + addressRecovered);
        }
        catch (SignatureException e)
        {
            e.printStackTrace();
        }

        return addressRecovered;
    }
}
