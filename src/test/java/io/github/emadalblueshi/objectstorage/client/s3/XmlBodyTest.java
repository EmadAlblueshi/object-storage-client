package io.github.emadalblueshi.objectstorage.client.s3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class XmlBodyTest {

  protected static XmlMapper xmlMapper;

  @BeforeAll
  static void setup() {
    xmlMapper = XmlMapper.builder()
        .addModule(new JavaTimeModule())
        .build();
  }

  @AfterAll
  static void clean() {
    xmlMapper = null;
  }

  @Test
  void testPolicyStatusTrue() throws Exception {
    String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<PolicyStatus xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<IsPublic>TRUE</IsPublic>" +
        "</PolicyStatus>";
    PolicyStatus policyStatus = xmlToObject(xmlString, PolicyStatus.class);
    assertTrue(policyStatus.isPublic());

  }

  @Test
  void testPolicyStatusFalse() throws Exception {
    String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<PolicyStatus xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<IsPublic>FALSE</IsPublic>" +
        "</PolicyStatus>";
    PolicyStatus policyStatus = xmlToObject(xmlString, PolicyStatus.class);
    assertFalse(policyStatus.isPublic());

  }

  @Test
  void testCopyObjectResult() throws Exception {
    String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<LastModified>2024-06-15T16:13:55.719Z</LastModified> " +
        "<ETag>&#34;5360706c803a759e3a9f2ca54a651950&#34;</ETag>" +
        "</CopyObjectResult>";

    CopyObjectResult copyObjectResult = xmlToObject(xmlString, CopyObjectResult.class);

    assertNotNull(copyObjectResult);
    assertNotNull(copyObjectResult.getLastModified());
    assertEquals("2024-06-15T16:13:55.719Z", copyObjectResult.getLastModified().toString());
    assertNotNull(copyObjectResult.getETag());
    assertEquals("\"5360706c803a759e3a9f2ca54a651950\"", copyObjectResult.getETag());
  }

  @Test
  void testAccessControlPolicy() throws Exception {
    String xmlString = "<AccessControlPolicy>" +
        "<Owner>" +
        "<ID>123</ID>" +
        "<DisplayName>Emad Alblueshi</DisplayName>" +
        "</Owner>" +
        "<AccessControlList>" +
        "<Grant>" +
        "<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">"
        +
        "<Type>CanonicalUser</Type>" +
        "</Grantee>" +
        "<Permission>FULL_CONTROL</Permission>" +
        "</Grant>" +
        "<Grant>" +
        "<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">"
        +
        "<Type>CanonicalUser</Type>" +
        "</Grantee>" +
        "<Permission>FULL_CONTROL</Permission>" +
        "</Grant>" +
        "</AccessControlList>" +
        "</AccessControlPolicy>";
    AccessControlPolicy accessControlPolicy = xmlToObject(xmlString, AccessControlPolicy.class);
    assertEquals("123", accessControlPolicy.getOwner().getId());
    assertEquals("Emad Alblueshi", accessControlPolicy.getOwner().getDisplayName());
    List<Grant> grantList = accessControlPolicy.getAccessControlList().getGrantList();
    assertEquals(2, grantList.size());
    assertEquals("FULL_CONTROL", grantList.get(0).getPermission());
    assertEquals("CanonicalUser", grantList.get(0).getGrantee().getAttributeType());
    assertEquals("FULL_CONTROL", grantList.get(1).getPermission());
    assertEquals("CanonicalUser", grantList.get(1).getGrantee().getAttributeType());
  }

  public <T> T xmlToObject(String xmlString, Class<T> clazz) throws Exception {
    T body = xmlMapper.<T>readValue(xmlString, clazz);
    return body;
  }
}
