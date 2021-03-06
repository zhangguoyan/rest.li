/*
   Copyright (c) 2012 LinkedIn Corp.

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

package com.linkedin.restli.client;


import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.Callbacks;
import com.linkedin.common.callback.FutureCallback;
import com.linkedin.common.util.None;
import com.linkedin.data.ByteString;
import com.linkedin.data.DataMap;
import com.linkedin.data.codec.JacksonDataCodec;
import com.linkedin.data.codec.PsonDataCodec;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.multipart.MultiPartMIMEUtils;
import com.linkedin.multipart.MultiPartMIMEWriter;
import com.linkedin.r2.disruptor.DisruptContext;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.MessageHeadersBuilder;
import com.linkedin.r2.message.Messages;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.stream.StreamRequest;
import com.linkedin.r2.message.stream.StreamRequestBuilder;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.message.stream.entitystream.ByteStringWriter;
import com.linkedin.restli.client.multiplexer.MultiplexedCallback;
import com.linkedin.restli.client.multiplexer.MultiplexedRequest;
import com.linkedin.restli.client.multiplexer.MultiplexedResponse;
import com.linkedin.restli.client.uribuilders.MultiplexerUriBuilder;
import com.linkedin.restli.client.uribuilders.RestliUriBuilderUtil;
import com.linkedin.restli.common.HttpMethod;
import com.linkedin.restli.common.OperationNameGenerator;
import com.linkedin.restli.common.ProtocolVersion;
import com.linkedin.restli.common.ResourceMethod;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.common.attachments.RestLiAttachmentDataSourceWriter;
import com.linkedin.restli.common.attachments.RestLiDataSourceIterator;
import com.linkedin.restli.disruptor.DisruptRestController;
import com.linkedin.restli.disruptor.DisruptRestControllerContainer;
import com.linkedin.restli.internal.client.RequestBodyTransformer;
import com.linkedin.restli.internal.client.ResponseFutureImpl;
import com.linkedin.restli.internal.common.AllProtocolVersions;
import com.linkedin.restli.internal.common.AttachmentUtils;
import com.linkedin.restli.internal.common.CookieUtil;
import com.linkedin.util.ArgumentUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.mail.internet.ParseException;


/**
 * Subset of Jersey's REST client, omitting things we probably won't use for internal API calls +
 * enforcing the use of Model (stenciled) response entities. We feature:
 *
 * <ul>
 * <li>Generic client interface (domain-specific hooks are via URIBuilders and Models)
 * <li>All request/response entities _must_ be 'Representation', which encapsultes a DataTemplate and hyperlinks
 * <li>Batch mode for all REST operations. This is inherently unRESTful, as you are supposed
 *     to operate on a single, named resource, NOT an arbitrary, ad-hoc collection of resources.
 *     For example, this probably breaks cacheability in proxies, as clients can specify an arbitrary
 *     set of IDs in the URI, in any order. Caching that specific batched response is pretty much useless,
 *     as no other client will probably look up those same IDs. Same for invalidating resources on POST/PUT.
 *     If we can add batch-aware proxies, this may work.
 *     In any case, we need batching to reduce latency, chattiness and framing overhead for calls made
 *     within and across our datacenters.
 *     Semantics of batch operations (atomic/non-atomic, etc) are specified by the server.
 * <li>Async invocation
 * <li>Client can choose to deal with the 'Response' (including status, headers) or just get the
 *     response entity (Model) directly (assuming response was status 200)
 * <li>TODO Exceptions at this layer? We clearly can't declare checked exceptions on THIS interface
 * </ul>
 *
 * <h2>Features NOT ported from Jersey client</h2>
 *
 * <ul>
 * <li>No support for String URIs
 * <li>Clients may define accept MIME types, the expected response entity type, and their preference order.
 *     By default clients will send no accept headers and receive responses in and expect the server to
 *     send responses in application/json format when there is no accept header.
 *  <li>TODO Do we need to support Accept-Language header for i18n profile use case?
 *  <li>No cookies
 *  <li>No (convenient) synchronous invocation mode (can just call Future.get())
 * </ul>
 *
 * <h2>Features in Jersey we should consider</h2>
 *
 * <ul>
 * <li>Standard a way to fetch 'links' from response entities.
 *     This would at least open the door for HATEOAS-style REST if we choose to use it in certain places (e.g.
 *     collection navigation, secondary actions (like, comment), etc)
 * </ul>
 *
 * @author dellamag
 * @author Eran Leshem
 */
public class RestClient implements Client {
  private static final JacksonDataCodec  JACKSON_DATA_CODEC = new JacksonDataCodec();
  private static final PsonDataCodec     PSON_DATA_CODEC    = new PsonDataCodec();
  private static final List<AcceptType>  DEFAULT_ACCEPT_TYPES = Collections.emptyList();
  private static final ContentType DEFAULT_CONTENT_TYPE = ContentType.JSON;
  private static final Random RANDOM_INSTANCE = new Random();
  private final com.linkedin.r2.transport.common.Client _client;

  private final String _uriPrefix;
  private final List<AcceptType> _acceptTypes;
  private final ContentType _contentType;
  // This is a system property that a user can set to override the protocol version handshake mechanism and always
  // use FORCE_USE_NEXT as the ProtocolVersionOption. If this system property is "true" (ignoring case) the override
  // is set. THIS SHOULD NOT BE USED IN PRODUCTION!
  private final boolean _forceUseNextVersionOverride =
      "true".equalsIgnoreCase(System.getProperty(RestConstants.RESTLI_FORCE_USE_NEXT_VERSION_OVERRIDE));

  public RestClient(com.linkedin.r2.transport.common.Client client, String uriPrefix)
  {
    this(client, uriPrefix, DEFAULT_CONTENT_TYPE, DEFAULT_ACCEPT_TYPES);
  }

  /**
   * @deprecated please use {@link RestliRequestOptions} to configure accept types.
   */
  @Deprecated
  public RestClient(com.linkedin.r2.transport.common.Client client,
      String uriPrefix, List<AcceptType> acceptTypes)
  {
    this(client, uriPrefix, DEFAULT_CONTENT_TYPE, acceptTypes);
  }

  /**
   * @deprecated please use {@link RestliRequestOptions} to configure content type and accept types.
   */
  @Deprecated
  public RestClient(com.linkedin.r2.transport.common.Client client,
      String uriPrefix, ContentType contentType, List<AcceptType> acceptTypes)
  {
    _client = client;
    _uriPrefix = (uriPrefix == null) ? null : uriPrefix.trim();
    _acceptTypes = acceptTypes;
    _contentType = contentType;
  }

  @Override
  public void shutdown(Callback<None> callback)
  {
    _client.shutdown(callback);
  }

  /**
   * @return The URI Prefix that this RestClient is using.
   * @deprecated Use PrefixAwareRestClient#getPrefix instead.
   */
  @Deprecated
  public String getURIPrefix() {
    return _uriPrefix;
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(Request<T> request, RequestContext requestContext)
  {
    FutureCallback<Response<T>> callback = new FutureCallback<Response<T>>();
    sendRequest(request, requestContext, callback);
    return new ResponseFutureImpl<T>(callback);
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(Request<T> request, RequestContext requestContext,
      ErrorHandlingBehavior errorHandlingBehavior)
  {
    FutureCallback<Response<T>> callback = new FutureCallback<Response<T>>();
    sendRequest(request, requestContext, callback);
    return new ResponseFutureImpl<T>(callback, errorHandlingBehavior);
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(RequestBuilder<? extends Request<T>> requestBuilder,
      RequestContext requestContext)
  {
    return sendRequest(requestBuilder.build(), requestContext);
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(RequestBuilder<? extends Request<T>> requestBuilder,
      RequestContext requestContext, ErrorHandlingBehavior errorHandlingBehavior)
  {
    return sendRequest(requestBuilder.build(), requestContext, errorHandlingBehavior);
  }

  @Override
  public <T> void sendRequest(final Request<T> request, final RequestContext requestContext,
      final Callback<Response<T>> callback)
  {
    //Here we need to decide if we want to use StreamRequest/StreamResponse or RestRequest/RestResponse.
    //Eventually we will move completely to StreamRequest/StreamResponse for all traffic.
    //However for the time being we will only use StreamRequest/StreamResponse for traffic that contains attachments.
    //
    //Therefore the decision is made as follows:
    //1. If the content-type OR accept-type is multipart/related then we use StreamRequest/StreamResponse,
    //otherwise we use RestRequest/RestResponse.
    //2. The content-type will be decided based on the presence of attachments in the request.
    //3. The accept-type will be based on the RestLiRequestOptions.

    //Note that it is not possible for the list of streaming attachments to be non-null and have 0 elements. If the
    //list of streaming attachments is non null then it must have at least one attachment. The request builders enforce
    //this invariant.
    if (request.getStreamingAttachments() != null || request.getRequestOptions().getAcceptResponseAttachments())
    {
      //Set content type and accept type correctly and use StreamRequest/StreamResponse
      sendStreamRequest(request, requestContext, new RestLiStreamCallbackAdapter<T>(request.getResponseDecoder(), callback));
    }
    else
    {
      sendRestRequest(request, requestContext, new RestLiCallbackAdapter<T>(request.getResponseDecoder(), callback));
    }
  }

  private <T> void sendStreamRequest(final Request<T> request,
                                     RequestContext requestContext,
                                     Callback<StreamResponse> callback)
  {
    RecordTemplate input = request.getInputRecord();
    ProtocolVersion protocolVersion = getProtocolVersionForService(request);
    URI requestUri = RestliUriBuilderUtil.createUriBuilder(request, _uriPrefix, protocolVersion).build();

    final ResourceMethod method = request.getMethod();
    final String methodName = request.getMethodName();
    addDisruptContext(request.getBaseUriTemplate(), method, methodName, requestContext);
    sendStreamRequestImpl(requestContext,
                          requestUri,
                          method,
                          input != null ? RequestBodyTransformer.transform(request, protocolVersion) : null,
                          request.getHeaders(),
                          CookieUtil.encodeCookies(request.getCookies()),
                          methodName,
                          protocolVersion,
                          request.getRequestOptions(),
                          request.getStreamingAttachments(),
                          callback);
  }

  /**
   * @deprecated as this API will change to private in a future release. Please use other APIs in this class, such as
   * {@link RestClient#sendRequest(Request,RequestContext, Callback)}
   * to send type-bound REST requests.
   *
   * Sends a type-bound REST request and answers on the provided callback.
   *
   * @param request to send
   * @param requestContext context for the request
   * @param callback to call on request completion
   */
  @Deprecated
  public <T> void sendRestRequest(final Request<T> request, RequestContext requestContext,
      Callback<RestResponse> callback)
  {
    //We need this until we remove the deprecation above since clients could attempt these:
    if (request.getStreamingAttachments() != null)
    {
      throw new UnsupportedOperationException("Cannot stream attachments using RestRequest/RestResponse!");
    }

    if (request.getRequestOptions() != null && request.getRequestOptions().getAcceptResponseAttachments())
    {
      throw new UnsupportedOperationException("Cannot expect streaming attachments using RestRequest/RestResponse!");
    }

    RecordTemplate input = request.getInputRecord();
    ProtocolVersion protocolVersion = getProtocolVersionForService(request);
    URI requestUri = RestliUriBuilderUtil.createUriBuilder(request, _uriPrefix, protocolVersion).build();

    final ResourceMethod method = request.getMethod();
    final String methodName = request.getMethodName();
    addDisruptContext(request.getBaseUriTemplate(), method, methodName, requestContext);
    sendRestRequestImpl(requestContext,
                        requestUri,
                        method,
                        input != null ? RequestBodyTransformer.transform(request, protocolVersion) : null, request.getHeaders(),
                        CookieUtil.encodeCookies(request.getCookies()),
                        methodName,
                        protocolVersion,
                        request.getRequestOptions(),
                        callback);
  }

  /**
   * @param request
   */
  private ProtocolVersion getProtocolVersionForService(final Request<?> request)
  {
    try
    {
      return getProtocolVersion(AllProtocolVersions.BASELINE_PROTOCOL_VERSION,
                                AllProtocolVersions.PREVIOUS_PROTOCOL_VERSION,
                                AllProtocolVersions.LATEST_PROTOCOL_VERSION,
                                AllProtocolVersions.NEXT_PROTOCOL_VERSION,
                                getAnnouncedVersion(_client.getMetadata(new URI(_uriPrefix + request.getServiceName()))),
                                request.getRequestOptions().getProtocolVersionOption(),
                                _forceUseNextVersionOverride);
    }
    catch (URISyntaxException e)
    {
      throw new RuntimeException("Failed to create a valid URI to fetch properties for!");
    }
  }

  /**
   * @param properties The server properties
   * @return the announced protocol version based on percentage
   */
  /*package private*/ static ProtocolVersion getAnnouncedVersion(Map<String, Object> properties)
  {
    if (properties == null)
    {
      throw new RuntimeException("No valid properties found!");
    }
    Object potentialAnnouncedVersion = properties.get(RestConstants.RESTLI_PROTOCOL_VERSION_PROPERTY);
    // if the server doesn't announce a protocol version we assume it is running the baseline version
    if (potentialAnnouncedVersion == null)
    {
      return AllProtocolVersions.BASELINE_PROTOCOL_VERSION;
    }
    Object potentialAnnouncedVersionPercentage = properties.get(RestConstants.RESTLI_PROTOCOL_VERSION_PERCENTAGE_PROPERTY);
    // if the server doesn't announce a protocol version percentage we assume it is running the announced version
    if (potentialAnnouncedVersionPercentage == null)
    {
      return new ProtocolVersion(potentialAnnouncedVersion.toString());
    }
    try
    {
      int announceVersionPercentage = Integer.parseInt(potentialAnnouncedVersionPercentage.toString());
      // if server announces percentage between 1 to 100 which is also below or equal to the generated probability, then we return announced version else the baseline
      return (announceVersionPercentage > 0 && announceVersionPercentage <= 100 && RANDOM_INSTANCE.nextInt(100) + 1 <= announceVersionPercentage) ?
          new ProtocolVersion(potentialAnnouncedVersion.toString()) : AllProtocolVersions.BASELINE_PROTOCOL_VERSION;
    }
    catch(NumberFormatException e)
    {
      // if the server announces a incorrect protocol version percentage we assume it is running the baseline version
      return AllProtocolVersions.BASELINE_PROTOCOL_VERSION;
    }
  }

  /**
   *
   * @param baselineProtocolVersion baseline version on the client
   * @param latestVersion latest version on the client
   * @param nextVersion the next version on the client
   * @param announcedVersion version announced by the service
   * @param versionOption options present on the request
   * @param forceUseNextVersionOverride if we always want to use {@link com.linkedin.restli.client.ProtocolVersionOption#FORCE_USE_NEXT}
   * @return the {@link ProtocolVersion} that should be used to build the request
   */
  /*package private*/static ProtocolVersion getProtocolVersion(ProtocolVersion baselineProtocolVersion,
                                                               ProtocolVersion previousVersion,
                                                               ProtocolVersion latestVersion,
                                                               ProtocolVersion nextVersion,
                                                               ProtocolVersion announcedVersion,
                                                               ProtocolVersionOption versionOption,
                                                               boolean forceUseNextVersionOverride)
  {
    if (versionOption == null)
    {
      throw new IllegalArgumentException("versionOptions cannot be null!");
    }
    if (forceUseNextVersionOverride)
    {
      return nextVersion;
    }
    switch (versionOption)
    {
      case FORCE_USE_NEXT:
        return nextVersion;
      case FORCE_USE_LATEST:
        return latestVersion;
      case FORCE_USE_PREVIOUS:
        return previousVersion;
      case USE_LATEST_IF_AVAILABLE:
        if (announcedVersion.compareTo(previousVersion) == -1)
        {
          // throw an exception as the announced version is less than the earliest supported version
          throw new RuntimeException("Announced version is less than the earliest supported version!" +
            "Announced version: " + announcedVersion + ", earliest supported version: " + previousVersion);
        }
        else if (announcedVersion.compareTo(previousVersion) == 0)
        {
          // server is running the earliest supported version
          return previousVersion;
        }
        else if (announcedVersion.compareTo(latestVersion) == -1)
        {
          // use the server announced version if it is less than the latest version
          return announcedVersion;
        }
        // server is either running the latest version or something newer. Use the latest version in this case.
        return latestVersion;
      default:
        return baselineProtocolVersion;
    }
  }

  // We handle accept types based on the following precedence order:
  // 1. Request header
  // 2. RestLiRequestOptions
  // 3. RestClient configuration
  private void addAcceptHeaders(MessageHeadersBuilder<?> builder, List<AcceptType> acceptTypes, boolean acceptAttachments)
  {
    if (builder.getHeader(RestConstants.HEADER_ACCEPT) == null)
    {
      List<AcceptType> types = _acceptTypes;
      if (acceptTypes != null && !acceptTypes.isEmpty())
      {
        types = acceptTypes;
      }
      if (types != null && !types.isEmpty())
      {
        builder.setHeader(RestConstants.HEADER_ACCEPT, createAcceptHeader(types, acceptAttachments));
      }
      else if (acceptAttachments)
      {
        builder.setHeader(RestConstants.HEADER_ACCEPT, createAcceptHeader(Collections.<AcceptType>emptyList(), acceptAttachments));
      }
    }
  }

  private String createAcceptHeader(List<AcceptType> acceptTypes, boolean acceptAttachments)
  {
    if (acceptTypes.size() == 1)
    {
      if (!acceptAttachments)
      {
        return acceptTypes.get(0).getHeaderKey();
      }
    }

    // general case
    StringBuilder acceptHeader = new StringBuilder();
    double currQ = 1.0;
    Iterator<AcceptType> iterator = acceptTypes.iterator();
    while(iterator.hasNext())
    {
      acceptHeader.append(iterator.next().getHeaderKey());
      acceptHeader.append(";q=");
      acceptHeader.append(currQ);
      currQ -= .1;
      if (iterator.hasNext())
        acceptHeader.append(",");
    }

    if (acceptAttachments)
    {
      if (acceptTypes.size() > 0)
      {
        acceptHeader.append(",");
      }
      acceptHeader.append(RestConstants.HEADER_VALUE_MULTIPART_RELATED);
      acceptHeader.append(";q=");
      acceptHeader.append(currQ);
    }
    return acceptHeader.toString();
  }


  // Request content type resolution follows similar precedence order to accept type:
  // 1. Request header
  // 2. RestLiRequestOption
  // 3. RestClient configuration
  private ContentType resolveContentType(MessageHeadersBuilder<?> builder, DataMap dataMap, ContentType contentType)
      throws IOException
  {
    if (dataMap != null)
    {
      String header = builder.getHeader(RestConstants.HEADER_CONTENT_TYPE);

      ContentType type;
      if (header == null)
      {
        if (contentType != null)
        {
          type = contentType;
        }
        else if (_contentType != null)
        {
          type = _contentType;
        }
        else {
          type = DEFAULT_CONTENT_TYPE;
        }
      }
      else
      {
        javax.mail.internet.ContentType headerContentType;
        try
        {
          headerContentType = new javax.mail.internet.ContentType(header);
        }
        catch (ParseException e)
        {
          throw new IllegalStateException("Unable to parse Content-Type: " + header);
        }

        if (headerContentType.getBaseType().equalsIgnoreCase(RestConstants.HEADER_VALUE_APPLICATION_JSON))
        {
          type = ContentType.JSON;
        }
        else if (headerContentType.getBaseType().equalsIgnoreCase(RestConstants.HEADER_VALUE_APPLICATION_PSON))
        {
          type = ContentType.PSON;
        }
        else
        {
          throw new IllegalStateException("Unknown Content-Type: " + headerContentType.toString());
        }
      }

      return type;
    }

    return null;
  }

  @Override
  public <T> void sendRequest(final RequestBuilder<? extends Request<T>> requestBuilder, RequestContext requestContext,
      Callback<Response<T>> callback)
  {
    sendRequest(requestBuilder.build(), requestContext, callback);
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(Request<T> request)
  {
    return sendRequest(request, new RequestContext());
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(Request<T> request, ErrorHandlingBehavior errorHandlingBehavior)
  {
    return sendRequest(request, new RequestContext(), errorHandlingBehavior);
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(RequestBuilder<? extends Request<T>> requestBuilder)
  {
    return sendRequest(requestBuilder.build(), new RequestContext());
  }

  @Override
  public <T> ResponseFuture<T> sendRequest(RequestBuilder<? extends Request<T>> requestBuilder,
      ErrorHandlingBehavior errorHandlingBehavior)
  {
    return sendRequest(requestBuilder.build(), new RequestContext(), errorHandlingBehavior);
  }

  @Override
  public <T> void sendRequest(final Request<T> request, Callback<Response<T>> callback)
  {
    sendRequest(request, new RequestContext(), callback);
  }

  @Override
  public <T> void sendRequest(final RequestBuilder<? extends Request<T>> requestBuilder, Callback<Response<T>> callback)
  {
    sendRequest(requestBuilder.build(), new RequestContext(), callback);
  }

  @Override
  public void sendRequest(MultiplexedRequest multiplexedRequest)
  {
    sendRequest(multiplexedRequest, Callbacks.<MultiplexedResponse>empty());
  }

  @Override
  public void sendRequest(MultiplexedRequest multiplexedRequest, Callback<MultiplexedResponse> callback)
  {
    sendRequest(multiplexedRequest, new RequestContext(), callback);
  }

  @Override
  public void sendRequest(MultiplexedRequest multiplexedRequest, RequestContext requestContext,
      Callback<MultiplexedResponse> callback)
  {
    MultiplexedCallback muxCallback = new MultiplexedCallback(multiplexedRequest.getCallbacks(), callback);
    addDisruptContext(MULTIPLEXER_RESOURCE, requestContext);
    try
    {
      RestRequest restRequest = buildMultiplexedRequest(multiplexedRequest);
      _client.restRequest(restRequest, requestContext, muxCallback);
    }
    catch (Exception e)
    {
      muxCallback.onError(e);
    }
  }

  private RestRequest buildMultiplexedRequest(MultiplexedRequest multiplexedRequest) throws IOException
  {
    URI requestUri = new MultiplexerUriBuilder(_uriPrefix).build();
    RestRequestBuilder requestBuilder = new RestRequestBuilder(requestUri).setMethod(HttpMethod.POST.toString());
    addAcceptHeaders(requestBuilder, Collections.singletonList(AcceptType.JSON), false);

    final DataMap multiplexedPayload = multiplexedRequest.getContent().data();
    final ContentType type = resolveContentType(requestBuilder, multiplexedPayload, ContentType.JSON);
    assert (type != null);
    requestBuilder.setHeader(RestConstants.HEADER_CONTENT_TYPE, type.getHeaderKey());
    switch (type)
    {
      case PSON:
        requestBuilder.setEntity(PSON_DATA_CODEC.mapToBytes(multiplexedPayload));
        break;
      case JSON:
        requestBuilder.setEntity(JACKSON_DATA_CODEC.mapToBytes(multiplexedPayload));
        break;
      default:
        throw new IllegalStateException("Unknown ContentType:" + type);
    }

    //TODO: change this once multiplexer supports dynamic versioning.
    requestBuilder.setHeader(RestConstants.HEADER_RESTLI_PROTOCOL_VERSION,
                             AllProtocolVersions.RESTLI_PROTOCOL_2_0_0.getProtocolVersion().toString());

    return requestBuilder.build();
  }

  /**
   * Sends an untyped REST request using a callback.
   *
   * @param requestContext context for the request
   * @param uri for resource
   * @param method to perform
   * @param dataMap request body entity
   * @param headers additional headers to be added to the request
   * @param cookies the cookies to be sent with the request
   * @param methodName the method name (used for finders and actions)
   * @param protocolVersion the version of the Rest.li protocol used to build this request
   * @param requestOptions contains compression force on/off overrides, request content type and accept types
   * @param callback to call on request completion. In the event of an error, the callback
   *                 will receive a {@link com.linkedin.r2.RemoteInvocationException}. If a valid
   *                 error response was received from the remote server, the callback will receive
   *                 a {@link com.linkedin.r2.message.rest.RestException} containing the error details.
   */
  private void sendRestRequestImpl(RequestContext requestContext,
                                   URI uri,
                                   ResourceMethod method,
                                   DataMap dataMap,
                                   Map<String, String> headers,
                                   List<String> cookies,
                                   String methodName,
                                   ProtocolVersion protocolVersion,
                                   RestliRequestOptions requestOptions,
                                   Callback<RestResponse> callback)
  {
    try
    {
      RestRequest request =
          buildRestRequest(uri, method, dataMap, headers, cookies, protocolVersion, requestOptions.getContentType(),
                           requestOptions.getAcceptTypes(), false);
      String operation = OperationNameGenerator.generate(method, methodName);
      requestContext.putLocalAttr(R2Constants.OPERATION, operation);
      requestContext.putLocalAttr(R2Constants.REQUEST_COMPRESSION_OVERRIDE, requestOptions.getRequestCompressionOverride());
      requestContext.putLocalAttr(R2Constants.RESPONSE_COMPRESSION_OVERRIDE, requestOptions.getResponseCompressionOverride());
      _client.restRequest(request, requestContext, callback);
    }
    catch (Exception e)
    {
      // No need to wrap the exception; RestLiCallbackAdapter.onError() will take care of that
      callback.onError(e);
    }
  }

  /**
   * Sends an untyped stream request using a callback.
   *
   * @param requestContext context for the request
   * @param uri for resource
   * @param method to perform
   * @param dataMap request body entity
   * @param headers additional headers to be added to the request
   * @param cookies the cookies to be sent with the request
   * @param methodName the method name (used for finders and actions)
   * @param protocolVersion the version of the Rest.li protocol used to build this request
   * @param requestOptions contains compression force on/off overrides, request content type and accept types
   * @param callback to call on request completion. In the event of an error, the callback
   *                 will receive a {@link com.linkedin.r2.RemoteInvocationException}. If a valid
   *                 error response was received from the remote server, the callback will receive
   *                 a {@link com.linkedin.r2.message.rest.RestException} containing the error details.
   */
  private void sendStreamRequestImpl(RequestContext requestContext,
                                     URI uri,
                                     ResourceMethod method,
                                     DataMap dataMap,
                                     Map<String, String> headers,
                                     List<String> cookies,
                                     String methodName,
                                     ProtocolVersion protocolVersion,
                                     RestliRequestOptions requestOptions,
                                     List<Object> streamingAttachments,
                                     Callback<StreamResponse> callback)
  {
    try
    {
      final StreamRequest request =
          buildStreamRequest(uri, method, dataMap, headers, cookies, protocolVersion, requestOptions.getContentType(),
                             requestOptions.getAcceptTypes(), requestOptions.getAcceptResponseAttachments(),
                             streamingAttachments);
      String operation = OperationNameGenerator.generate(method, methodName);
      requestContext.putLocalAttr(R2Constants.OPERATION, operation);
      requestContext.putLocalAttr(R2Constants.REQUEST_COMPRESSION_OVERRIDE, requestOptions.getRequestCompressionOverride());
      requestContext.putLocalAttr(R2Constants.RESPONSE_COMPRESSION_OVERRIDE,
                                  requestOptions.getResponseCompressionOverride());
      _client.streamRequest(request, requestContext, callback);
    }
    catch (Exception e)
    {
      // No need to wrap the exception; RestLiCallbackAdapter.onError() will take care of that
      callback.onError(e);
    }
  }

  // This throws Exception to remind the caller to deal with arbitrary exceptions including RuntimeException
  // in a way appropriate for the public method that was originally invoked.
  private RestRequest buildRestRequest(URI uri,
                                       ResourceMethod method,
                                       DataMap dataMap,
                                       Map<String, String> headers,
                                       List<String> cookies,
                                       ProtocolVersion protocolVersion,
                                       ContentType contentType,
                                       List<AcceptType> acceptTypes,
                                       boolean acceptResponseAttachments) throws Exception
  {
    RestRequestBuilder requestBuilder = new RestRequestBuilder(uri).setMethod(method.getHttpMethod().toString());

    requestBuilder.setHeaders(headers);
    requestBuilder.setCookies(cookies);

    addAcceptHeaders(requestBuilder, acceptTypes, acceptResponseAttachments);

    final ContentType type = resolveContentType(requestBuilder, dataMap, contentType);
    if (type != null)
    {
      requestBuilder.setHeader(RestConstants.HEADER_CONTENT_TYPE, type.getHeaderKey());
      switch (type)
      {
        case PSON:
          requestBuilder.setEntity(PSON_DATA_CODEC.mapToBytes(dataMap));
          break;
        case JSON:
          requestBuilder.setEntity(JACKSON_DATA_CODEC.mapToBytes(dataMap));
          break;
        default:
          throw new IllegalStateException("Unknown ContentType:" + type);
      }
    }

    addProtocolVersionHeader(requestBuilder, protocolVersion);

    if (method.getHttpMethod() == HttpMethod.POST)
    {
      requestBuilder.setHeader(RestConstants.HEADER_RESTLI_REQUEST_METHOD, method.toString());
    }

    return requestBuilder.build();
  }

  private StreamRequest buildStreamRequest(URI uri,
                                           ResourceMethod method,
                                           DataMap dataMap,
                                           Map<String, String> headers,
                                           List<String> cookies,
                                           ProtocolVersion protocolVersion,
                                           ContentType contentType,
                                           List<AcceptType> acceptTypes,
                                           boolean acceptResponseAttachments,
                                           List<Object> streamingAttachments) throws Exception
  {
    StreamRequestBuilder requestBuilder = new StreamRequestBuilder(uri).setMethod(method.getHttpMethod().toString());

    requestBuilder.setHeaders(headers);
    requestBuilder.setCookies(cookies);

    addAcceptHeaders(requestBuilder, acceptTypes, acceptResponseAttachments);
    addProtocolVersionHeader(requestBuilder, protocolVersion);

    if (method.getHttpMethod() == HttpMethod.POST)
    {
      requestBuilder.setHeader(RestConstants.HEADER_RESTLI_REQUEST_METHOD, method.toString());
    }

    //If we have attachments outbound we use multipart related. If we don't, we just stream out our traditional
    //wire protocol. Also note that it is not possible for streaming attachments to be non-null and have 0 attachments.
    //This request builders enforce this invariant.
    if (streamingAttachments != null)
    {
      final ByteStringWriter firstPartWriter;
      final ContentType type = resolveContentType(requestBuilder, dataMap, contentType);
      //This assertion holds true since there will be a non null dataMap (payload) for all requests which are are
      //eligible to have attachments. This is because all such requests are POST or PUTs. Even an action request
      //with empty action parameters will have an empty JSON ({}) as the body.
      assert (type != null);
      switch (type)
      {
        case PSON:
          firstPartWriter = new ByteStringWriter(ByteString.copy(PSON_DATA_CODEC.mapToBytes(dataMap)));
          break;
        case JSON:
          firstPartWriter = new ByteStringWriter(ByteString.copy(JACKSON_DATA_CODEC.mapToBytes(dataMap)));
          break;
        default:
          throw new IllegalStateException("Unknown ContentType:" + type);
      }

      //Our protocol does not use an epilogue or a preamble.
      final MultiPartMIMEWriter.Builder attachmentsBuilder = new MultiPartMIMEWriter.Builder();

      for (final Object dataSource : streamingAttachments)
      {
        assert(dataSource instanceof RestLiAttachmentDataSourceWriter || dataSource instanceof RestLiDataSourceIterator);

        if (dataSource instanceof RestLiAttachmentDataSourceWriter)
        {
          AttachmentUtils.appendSingleAttachmentToBuilder(attachmentsBuilder, (RestLiAttachmentDataSourceWriter) dataSource);
        }
        else
        {
          AttachmentUtils.appendMultipleAttachmentsToBuilder(attachmentsBuilder, (RestLiDataSourceIterator) dataSource);
        }
      }

      final MultiPartMIMEWriter multiPartMIMEWriter =
          AttachmentUtils.createMultiPartMIMEWriter(firstPartWriter, type.getHeaderKey(), attachmentsBuilder);

      final String contentTypeHeader =
          MultiPartMIMEUtils.buildMIMEContentTypeHeader(AttachmentUtils.RESTLI_MULTIPART_SUBTYPE, multiPartMIMEWriter.getBoundary(),
                                                        Collections.<String, String> emptyMap());

      requestBuilder.setHeader(MultiPartMIMEUtils.CONTENT_TYPE_HEADER, contentTypeHeader);
      return requestBuilder.build(multiPartMIMEWriter.getEntityStream());
    }
    else
    {
      //Note that for it to reach this state, acceptResponseAttachments must be true. Otherwise there are no request
      //attachments and there is no desire to receive response attachments, which means this request should have directly
      //taken the RestRequest code path.
      assert(acceptResponseAttachments == true);

      return Messages.toStreamRequest(buildRestRequest(uri, method, dataMap, headers, cookies,
                                                       protocolVersion, contentType, acceptTypes,
                                                       acceptResponseAttachments));
    }
  }

  /**
   * Adds the protocol version of Rest.li used to build the request to the headers for this request
   * @param builder
   * @param protocolVersion
   */
  private void addProtocolVersionHeader(MessageHeadersBuilder<?> builder, ProtocolVersion protocolVersion)
  {
    builder.setHeader(RestConstants.HEADER_RESTLI_PROTOCOL_VERSION, protocolVersion.toString());
  }

  /**
   * Evaluates a {@link Request} against the {@link DisruptRestController} and stores the resolved {@link DisruptContext}
   * to the {@link RequestContext} if the resolved DisruptContext is not {@code null}
   *
   * @param resource Resource name
   * @param requestContext Request context
   */
  private void addDisruptContext(String resource, RequestContext requestContext)
  {
    addDisruptContext(resource, null, null, requestContext);
  }

  /**
   * Evaluates a {@link Request} against the {@link DisruptRestController} and stores the resolved {@link DisruptContext}
   * to the {@link RequestContext} if the resolved DisruptContext is not {@code null}
   *
   * @param resource Resource name
   * @param method Resource method
   * @param name Name of the finder or action
   * @param requestContext Request context
   */
  private void addDisruptContext(String resource, ResourceMethod method, String name, RequestContext requestContext)
  {
    final DisruptRestController controller = DisruptRestControllerContainer.getInstance();
    if (controller == null)
    {
      return;
    }

    // Do not evaluate further if disrupt source key is already present in the request context
    if (requestContext.getLocalAttr(DisruptContext.DISRUPT_SOURCE_KEY) != null)
    {
      return;
    }

    ArgumentUtil.notNull(resource, "resource");

    final DisruptContext disruptContext;
    if (method == null)
    {
      disruptContext = controller.getDisruptContext(resource);
    }
    else
    {
      if (name == null)
      {
        disruptContext = controller.getDisruptContext(resource, method);
      }
      else
      {
        disruptContext = controller.getDisruptContext(resource, method, name);
      }
    }

    requestContext.putLocalAttr(DisruptContext.DISRUPT_SOURCE_KEY, controller.getClass().getCanonicalName());
    if (disruptContext != null)
    {
      requestContext.putLocalAttr(DisruptContext.DISRUPT_CONTEXT_KEY, disruptContext);
    }
  }

  public static enum AcceptType
  {
    PSON(RestConstants.HEADER_VALUE_APPLICATION_PSON),
    JSON(RestConstants.HEADER_VALUE_APPLICATION_JSON),
    ANY(RestConstants.HEADER_VALUE_ACCEPT_ANY);

    private String _headerKey;

    private AcceptType(String headerKey)
    {
      _headerKey = headerKey;
    }

    public String getHeaderKey()
    {
      return _headerKey;
    }
  }

  public static enum ContentType
  {
    PSON(RestConstants.HEADER_VALUE_APPLICATION_PSON),
    JSON(RestConstants.HEADER_VALUE_APPLICATION_JSON);

    private String _headerKey;

    private ContentType(String headerKey)
    {
      _headerKey = headerKey;
    }

    public String getHeaderKey()
    {
      return _headerKey;
    }
  }
}
