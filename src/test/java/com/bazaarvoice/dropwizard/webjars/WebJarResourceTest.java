package com.bazaarvoice.dropwizard.webjars;

import org.junit.Test;

import javax.ws.rs.core.Response;

import static org.junit.Assert.assertTrue;

public class WebJarResourceTest {

    @Test
    public void ensureEasyModeWorks() throws Exception {
        WebJarResource webJarResource = new WebJarResource();
        Response response = webJarResource.getAsset("bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);

        response = webJarResource.getAsset("bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);
    }

    @Test
    public void ensureSimpleBuilderWorks() throws Exception {
        WebJarResource webJarResource = new WebJarResourceBuilder().build();
        Response response = webJarResource.getAsset("bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);

        response = webJarResource.getAsset("bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);
    }

    @Test
    public void ensureNonStandardPathFails() throws Exception {
        WebJarResource webJarResource = new WebJarResourceBuilder().build();
        Response response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null); // should 404
        assertTrue(response.getStatus() == 404);

        response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null); // should 404
        assertTrue(response.getStatus() == 404);
    }

    @Test
    public void ensureNonStandardPathWorks() throws Exception {
        WebJarResource webJarResource = new WebJarResourceBuilder().withAdditionalWebjarPackage("com.example.webjars").build();
        Response response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);

        response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() < 400);
    }

    @Test
    public void ensureCrummyPathDoesntDie() throws Exception {
        WebJarResource webJarResource = new WebJarResourceBuilder().withAdditionalWebjarPackage("com.crummy.webjars").build();
        Response response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() == 404);

        response = webJarResource.getAsset("another-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() == 404);
    }

    @Test
    public void ensureCrummyPathAndBadAssetDoesntDie() throws Exception {
        WebJarResource webJarResource = new WebJarResourceBuilder().withAdditionalWebjarPackage("com.crummy.webjars").build();
        Response response = webJarResource.getAsset("non-existent-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() == 404);

        response = webJarResource.getAsset("non-existent-bootstrap", "css/bootstrap.css", null, null);
        assertTrue(response.getStatus() == 404);
    }
}
