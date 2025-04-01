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
    S3PolicyStatus s3PolicyStatus = xmlToObject(xmlString, S3PolicyStatus.class);
    assertTrue(s3PolicyStatus.isPublic());

  }

  @Test
  void testPolicyStatusFalse() throws Exception {
    String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<PolicyStatus xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<IsPublic>FALSE</IsPublic>" +
        "</PolicyStatus>";
    S3PolicyStatus s3PolicyStatus = xmlToObject(xmlString, S3PolicyStatus.class);
    assertFalse(s3PolicyStatus.isPublic());

  }

  @Test
  void testCopyObjectResult() throws Exception {
    String xmlString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<LastModified>2024-06-15T16:13:55.719Z</LastModified> " +
        "<ETag>&#34;5360706c803a759e3a9f2ca54a651950&#34;</ETag>" +
        "</CopyObjectResult>";

    S3CopyObjectResult s3CopyObjectResult = xmlToObject(xmlString, S3CopyObjectResult.class);

    assertNotNull(s3CopyObjectResult);
    assertNotNull(s3CopyObjectResult.getLastModified());
    assertEquals("2024-06-15T16:13:55.719Z", s3CopyObjectResult.getLastModified().toString());
    assertNotNull(s3CopyObjectResult.getETag());
    assertEquals("\"5360706c803a759e3a9f2ca54a651950\"", s3CopyObjectResult.getETag());
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
    S3AccessControlPolicy s3AccessControlPolicy = xmlToObject(xmlString, S3AccessControlPolicy.class);
    assertEquals("123", s3AccessControlPolicy.getOwner().getId());
    assertEquals("Emad Alblueshi", s3AccessControlPolicy.getOwner().getDisplayName());
    List<S3Grant> s3GrantList = s3AccessControlPolicy.getAccessControlList().getGrantList();
    assertEquals(2, s3GrantList.size());
    assertEquals("FULL_CONTROL", s3GrantList.get(0).getPermission());
    assertEquals("CanonicalUser", s3GrantList.get(0).getGrantee().getAttributeType());
    assertEquals("FULL_CONTROL", s3GrantList.get(1).getPermission());
    assertEquals("CanonicalUser", s3GrantList.get(1).getGrantee().getAttributeType());
  }

  public <T> T xmlToObject(String xmlString, Class<T> clazz) throws Exception {
    T body = xmlMapper.<T>readValue(xmlString, clazz);
    return body;
  }
}
