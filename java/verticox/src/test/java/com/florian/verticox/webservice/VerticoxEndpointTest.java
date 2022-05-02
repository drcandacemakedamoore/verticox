package com.florian.verticox.webservice;

import com.florian.nscalarproduct.data.Attribute;
import com.florian.nscalarproduct.encryption.AES;
import com.florian.nscalarproduct.webservice.ServerEndpoint;
import com.florian.nscalarproduct.webservice.domain.AttributeRequirement;
import com.florian.verticox.webservice.domain.SetValuesRequest;
import com.florian.verticox.webservice.domain.SumRelevantValuesRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VerticoxEndpointTest {

    private Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");

    public VerticoxEndpointTest() throws NoSuchPaddingException, NoSuchAlgorithmException {
    }

    @Test
    public void testValuesMultiplication()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer serverZ = new VerticoxServer("resources/smallK2Example_secondhalf.csv", "Z");
        VerticoxEndpoint endpointZ = new VerticoxEndpoint(serverZ);

        VerticoxServer server2 = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "2");
        VerticoxEndpoint endpoint2 = new VerticoxEndpoint(server2);

        BigDecimal[] values = new BigDecimal[10];
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            values[i] = BigDecimal.valueOf(r.nextDouble());
        }

        endpointZ.setValues(createSetValuesRequest(endpointZ, values));

        AttributeRequirement req = new AttributeRequirement();
        req.setValue(new Attribute(Attribute.AttributeType.numeric, "1", "x1"));
        //this selects individuals: 1,2,4,7, & 9
        BigDecimal expected = BigDecimal.ZERO;
        expected = expected.add(values[0]).add(values[1]).add(values[3]).add(values[6]).add(values[8]);

        VerticoxServer secret = new VerticoxServer("3", Arrays.asList(endpointZ, endpoint2));
        ServerEndpoint secretEnd = new ServerEndpoint(secret);

        List<ServerEndpoint> all = new ArrayList<>();
        all.add(endpointZ);
        all.add(endpoint2);
        all.add(secretEnd);
        secret.setEndpoints(all);
        serverZ.setEndpoints(all);
        server2.setEndpoints(all);

        VerticoxCentralServer central = new VerticoxCentralServer(true);

        int precision = 5;
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, precision));
        endpointZ.setPrecision(precision);
        endpoint2.setPrecision(precision);
        central.setPrecision(precision);
        secret.setPrecision(precision);

        central.initEndpoints(Arrays.asList(endpointZ, endpoint2), secretEnd);
        SumRelevantValuesRequest request = new SumRelevantValuesRequest();
        request.setValueServer("z");
        request.setRequirements(Arrays.asList(req));
        BigDecimal result = central.sumRelevantValues(request);

        assertEquals(result.longValue(), expected.longValue(), multiplier.longValue());
    }

    @Test
    public void testMinimumPeriod()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer smalExample = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "Z");
        VerticoxEndpoint endpointsmall = new VerticoxEndpoint(smalExample);

        VerticoxServer bigExample = new VerticoxServer("resources/bigK2Example_firsthalf.csv", "Z");
        VerticoxEndpoint endpointBig = new VerticoxEndpoint(bigExample);


        AttributeRequirement resultSmall = endpointsmall.determineMinimumPeriod(
                new Attribute(Attribute.AttributeType.numeric, "0", "x1"));
        AttributeRequirement resultBigStarting0 = endpointBig.determineMinimumPeriod(
                new Attribute(Attribute.AttributeType.numeric, "0", "x1"));
        AttributeRequirement resultBigStarting1 = endpointBig.determineMinimumPeriod(
                new Attribute(Attribute.AttributeType.numeric, "1", "x1"));
        AttributeRequirement resultBigStarting2 = endpointBig.determineMinimumPeriod(
                new Attribute(Attribute.AttributeType.numeric, "2", "x1"));
        AttributeRequirement resultBigStarting3 = endpointBig.determineMinimumPeriod(
                new Attribute(Attribute.AttributeType.numeric, "3", "x1"));

        assertEquals(resultSmall.getLowerLimit().getValue(), "0");
        assertEquals(resultSmall.getUpperLimit().getValue(), "1");

        //only 1 example has value 0, so minimum requirement will be [0-2)
        assertEquals(resultBigStarting0.getLowerLimit().getValue(), "0");
        assertEquals(resultBigStarting0.getUpperLimit().getValue(), "2");
        //13 examples have value 1, so value will be used, not range
        assertEquals(resultBigStarting1.getValue().getValue(), "1");
        //5 examples have value 2, so minimum requirement will be [2-3)
        assertEquals(resultBigStarting2.getLowerLimit().getValue(), "2");
        assertEquals(resultBigStarting2.getUpperLimit().getValue(), "3");
        //1 example has value 3, but its also the maximum value, so the minimum requirement will be 3 on it's own
        assertEquals(resultBigStarting3.getValue().getValue(), "3");
    }

    private SetValuesRequest createSetValuesRequest(VerticoxEndpoint endpointZ, BigDecimal[] values) {
        SetValuesRequest req = new SetValuesRequest();
        try {
            AES aes = new AES();
            byte[] rsaPublicKey = endpointZ.getPublicKey().getKey();
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(rsaPublicKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey pubKey = keyFactory.generatePublic(keySpec);
            cipher.init(Cipher.ENCRYPT_MODE, pubKey);
            String[] encrypted = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                encrypted[i] = aes.encrypt(values[i]);
            }
            req.setValues(encrypted);
            req.setEncryptedAes(cipher.doFinal(aes.getKey().getEncoded()));
        } catch (Exception e) {

        }
        return req;
    }

    @Test
    public void testValuesMultiplicationHybridSplit()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer serverZ = new VerticoxServer("resources/hybridsplit/smallK2Example_firsthalf.csv", "Z");
        VerticoxEndpoint endpointZ = new VerticoxEndpoint(serverZ);

        VerticoxServer server2 = new VerticoxServer("resources/hybridsplit/smallK2Example_secondhalf.csv", "2");
        VerticoxEndpoint endpoint2 = new VerticoxEndpoint(server2);

        VerticoxServer server3 = new VerticoxServer(
                "resources/hybridsplit/smallK2Example_secondhalf_morepopulation.csv", "3");
        VerticoxEndpoint endpoint3 = new VerticoxEndpoint(server3);

        BigDecimal[] values = new BigDecimal[10];
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            values[i] = BigDecimal.valueOf(r.nextDouble());
        }
        endpointZ.setValues(createSetValuesRequest(endpointZ, values));

        AttributeRequirement req = new AttributeRequirement();
        req.setValue(new Attribute(Attribute.AttributeType.numeric, "1", "x2"));
        //this selects individuals: 2,3,4,6,7, & 9
        BigDecimal expected = BigDecimal.ZERO;
        expected = expected.add(values[1]).add(values[2]).add(values[3]).add(values[5]).add(values[7]).add(values[8]);

        VerticoxServer secret = new VerticoxServer("4", Arrays.asList(endpointZ, endpoint2, endpoint3));
        ServerEndpoint secretEnd = new ServerEndpoint(secret);

        List<ServerEndpoint> all = new ArrayList<>();
        all.add(endpointZ);
        all.add(endpoint2);
        all.add(endpoint3);
        all.add(secretEnd);
        secret.setEndpoints(all);
        serverZ.setEndpoints(all);
        server2.setEndpoints(all);
        server3.setEndpoints(all);

        VerticoxCentralServer central = new VerticoxCentralServer(true);

        int precision = 5;
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, precision));
        endpointZ.setPrecision(precision);
        endpoint2.setPrecision(precision);
        central.setPrecision(precision);
        secret.setPrecision(precision);

        central.initEndpoints(Arrays.asList(endpointZ, endpoint2), secretEnd);
        SumRelevantValuesRequest request = new SumRelevantValuesRequest();
        request.setValueServer("z");
        request.setRequirements(Arrays.asList(req));
        BigDecimal result = central.sumRelevantValues(request);

        assertEquals(result.longValue(), expected.longValue(), multiplier.longValue());
    }

    @Test
    public void testValuesMultiplicationThreeServers()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer serverZ = new VerticoxServer("resources/smallK2Example_secondhalf.csv", "Z");
        VerticoxEndpoint endpointZ = new VerticoxEndpoint(serverZ);

        VerticoxServer server2 = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "2");
        VerticoxEndpoint endpoint2 = new VerticoxEndpoint(server2);
        VerticoxServer server3 = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "2");
        VerticoxEndpoint endpoint3 = new VerticoxEndpoint(server3);

        BigDecimal[] values = new BigDecimal[10];
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            values[i] = BigDecimal.valueOf(r.nextDouble());
        }
        endpointZ.setValues(createSetValuesRequest(endpointZ, values));

        AttributeRequirement req = new AttributeRequirement();
        req.setValue(new Attribute(Attribute.AttributeType.numeric, "1", "x1"));
        //this selects individuals: 1,2,4,7, & 9
        BigDecimal expected = BigDecimal.ZERO;
        expected = expected.add(values[0]).add(values[1]).add(values[3]).add(values[6]).add(values[8]);

        VerticoxServer secret = new VerticoxServer("3", Arrays.asList(endpointZ, endpoint2, endpoint3));
        ServerEndpoint secretEnd = new ServerEndpoint(secret);

        List<ServerEndpoint> all = new ArrayList<>();
        all.add(endpointZ);
        all.add(endpoint2);
        all.add(endpoint3);
        all.add(secretEnd);
        secret.setEndpoints(all);
        serverZ.setEndpoints(all);
        server2.setEndpoints(all);
        server3.setEndpoints(all);

        VerticoxCentralServer central = new VerticoxCentralServer(true);

        int precision = 5;
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, precision));
        endpointZ.setPrecision(precision);
        endpoint2.setPrecision(precision);
        endpoint3.setPrecision(precision);
        central.setPrecision(precision);
        secret.setPrecision(precision);

        central.initEndpoints(Arrays.asList(endpointZ, endpoint2), secretEnd);
        SumRelevantValuesRequest request = new SumRelevantValuesRequest();
        request.setValueServer("z");
        request.setRequirements(Arrays.asList(req));
        BigDecimal result = central.sumRelevantValues(request);

        assertEquals(result.longValue(), expected.longValue(), multiplier.longValue());
    }

    @Test
    public void testValuesMultiplicationInfiniteRange()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer serverZ = new VerticoxServer("resources/smallK2Example_secondhalf.csv", "Z");
        VerticoxEndpoint endpointZ = new VerticoxEndpoint(serverZ);

        VerticoxServer server2 = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "2");
        VerticoxEndpoint endpoint2 = new VerticoxEndpoint(server2);

        BigDecimal[] values = new BigDecimal[10];
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            values[i] = BigDecimal.valueOf(r.nextDouble());
        }
        endpointZ.setValues(createSetValuesRequest(endpointZ, values));

        AttributeRequirement req = new AttributeRequirement(new Attribute(Attribute.AttributeType.numeric, "0", "x1"),
                                                            new Attribute(Attribute.AttributeType.numeric, "inf",
                                                                          "x1"));
        //this selects all individuals
        BigDecimal expected = Arrays.stream(values).reduce(BigDecimal.ZERO, BigDecimal::add);

        VerticoxServer secret = new VerticoxServer("3", Arrays.asList(endpointZ, endpoint2));
        ServerEndpoint secretEnd = new ServerEndpoint(secret);

        List<ServerEndpoint> all = new ArrayList<>();
        all.add(endpointZ);
        all.add(endpoint2);
        all.add(secretEnd);
        secret.setEndpoints(all);
        serverZ.setEndpoints(all);
        server2.setEndpoints(all);

        VerticoxCentralServer central = new VerticoxCentralServer(true);

        int precision = 5;
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, precision));
        endpointZ.setPrecision(precision);
        endpoint2.setPrecision(precision);
        central.setPrecision(precision);
        secret.setPrecision(precision);

        central.initEndpoints(Arrays.asList(endpointZ, endpoint2), secretEnd);
        SumRelevantValuesRequest request = new SumRelevantValuesRequest();
        request.setValueServer("z");
        request.setRequirements(Arrays.asList(req));
        BigDecimal result = central.sumRelevantValues(request);

        assertEquals(result.longValue(), expected.longValue(), multiplier.longValue());
    }

    @Test
    public void testValuesMultiplicationDataAndCriteriaInSamePlace()
            throws NoSuchPaddingException, UnsupportedEncodingException, NoSuchAlgorithmException {
        VerticoxServer serverZ = new VerticoxServer("resources/smallK2Example_secondhalf.csv", "Z");
        VerticoxEndpoint endpointZ = new VerticoxEndpoint(serverZ);

        VerticoxServer server2 = new VerticoxServer("resources/smallK2Example_firsthalf.csv", "2");
        VerticoxEndpoint endpoint2 = new VerticoxEndpoint(server2);

        BigDecimal[] values = new BigDecimal[10];
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            values[i] = BigDecimal.valueOf(r.nextDouble());
        }
        endpointZ.setValues(createSetValuesRequest(endpointZ, values));

        AttributeRequirement req = new AttributeRequirement(new Attribute(Attribute.AttributeType.numeric, "0", "x1"),
                                                            new Attribute(Attribute.AttributeType.numeric, "inf",
                                                                          "x1"));
        //this selects all individuals
        BigDecimal expected = Arrays.stream(values).reduce(BigDecimal.ZERO, BigDecimal::add);

        VerticoxServer secret = new VerticoxServer("3", Arrays.asList(endpointZ, endpoint2));
        ServerEndpoint secretEnd = new ServerEndpoint(secret);

        List<ServerEndpoint> all = new ArrayList<>();
        all.add(endpointZ);
        all.add(endpoint2);
        all.add(secretEnd);
        secret.setEndpoints(all);
        serverZ.setEndpoints(all);
        server2.setEndpoints(all);

        VerticoxCentralServer central = new VerticoxCentralServer(true);

        int precision = 5;
        BigDecimal multiplier = BigDecimal.valueOf(Math.pow(10, precision));
        endpointZ.setPrecision(precision);
        endpoint2.setPrecision(precision);
        central.setPrecision(precision);
        secret.setPrecision(precision);

        central.initEndpoints(Arrays.asList(endpointZ, endpoint2), secretEnd);
        SumRelevantValuesRequest request = new SumRelevantValuesRequest();
        request.setValueServer("z");
        request.setRequirements(Arrays.asList(req));
        BigDecimal result = central.sumRelevantValues(request);

        assertEquals(result.longValue(), expected.longValue(), multiplier.longValue());
    }

}