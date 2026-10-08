package cc.serverless;

import java.util.Optional;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import cc.data.Auction;
import cc.data.Bid;
import cc.ops.BidOps;
import cc.data.Login;
import cc.data.Question;
import cc.ops.QuestionOps;
import cc.data.User;
import cc.ops.AuctionOps;
import cc.ops.UserOps;
import cc.utils.Result;
import cc.data.MediaDAO;
import cc.ops.MediaOps;

/**
 * Functions with an HTTP trigger, reachable at {Server_URL}/api/{route}. The
 * full URL is
 * printed when you deploy.
 *
 * There is one function here, matching /rest/ctrl/version in the main project,
 * so that you
 * have the same endpoint on both deployments to compare. The rest of the
 * project is for you
 * to add: take the operations in cc.ops and put an HTTP trigger in front of
 * each one.
 *
 * The operations need nothing from JAX-RS, which is the point of keeping them
 * in cc.ops -
 * a session is a String and errors come back in a Result, so a function reads
 * the status
 * code and builds its own response:
 *
 * Result<User> r = UserOps.getInstance().get(id);
 * return r.isOK()
 * ? request.createResponseBuilder(HttpStatus.OK).body(r.value()).build()
 * : request.createResponseBuilder(HttpStatus.valueOf(r.error())).build();
 */
public class HttpFunction {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static String sessionId(HttpRequestMessage<?> request) {
		Map<String, String> headers = request.getHeaders();
		String cookie = headers.get("Cookie");

		if (cookie == null)
			cookie = headers.get("cookie");

		if (cookie == null)
			return null;

		for (String part : cookie.split(";")) {
			part = part.trim();

			if (part.startsWith("cc:session="))
				return part.substring("cc:session=".length());
		}

		return null;
	}

	private static int queryInt(
			HttpRequestMessage<?> request,
			String name,
			int defaultValue) {

		String value = request.getQueryParameters().get(name);

		if (value == null || value.isEmpty())
			return defaultValue;

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String queryString(
			HttpRequestMessage<?> request,
			String name,
			String defaultValue) {

		String value = request.getQueryParameters().get(name);

		if (value == null)
			return defaultValue;

		return value;
	}

	private static String pathPart(
			HttpRequestMessage<?> request,
			int fromEnd) {

		String path = request.getUri().getPath();
		String[] parts = path.split("/");

		return parts[parts.length - fromEnd];
	}

	private static <T> T body(
			HttpRequestMessage<Optional<String>> request,
			Class<T> type) throws Exception {

		return MAPPER.readValue(
				request.getBody().orElse(""),
				type);
	}

	private static <T> HttpResponseMessage response(
			HttpRequestMessage<?> request,
			Result<T> result) {

		if (result.isOK()) {
			if (result.value() == null)
				return request.createResponseBuilder(
						HttpStatus.valueOf(result.error()))
						.build();

			return request.createResponseBuilder(HttpStatus.OK)
					.body(result.value())
					.build();
		}

		return request.createResponseBuilder(
				HttpStatus.valueOf(result.error()))
				.build();
	}

	@FunctionName("ctrl-version")
	public HttpResponseMessage version(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "ctrl/version") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		context.getLogger().info("ctrl/version called");

		return request.createResponseBuilder(HttpStatus.OK)
				.body("cc2627-proj-fun v1")
				.build();
	}

	@FunctionName("user-get")
	public HttpResponseMessage getUser(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user/{id}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String path = request.getUri().getPath();
		String[] parts = path.split("/");

		// Expected path: /api/user/{id}
		String id = parts[parts.length - 1];

		context.getLogger().info("GET /user/" + id);

		Result<User> result = UserOps.getInstance().get(id);

		if (result.isOK()) {
			return request.createResponseBuilder(HttpStatus.OK)
					.body(result.value())
					.build();
		}

		return request.createResponseBuilder(
				HttpStatus.valueOf(result.error()))
				.build();
	}

	@FunctionName("user-create")
	public HttpResponseMessage createUser(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		context.getLogger().info("POST /user");

		try {
			User user = body(request, User.class);
			Result<User> result = UserOps.getInstance().create(user);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid user JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("user-auth")
	public HttpResponseMessage authUser(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user/auth") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		context.getLogger().info("POST /user/auth");

		try {
			Login login = body(request, Login.class);
			Result<String> result = UserOps.getInstance().auth(login);

			if (!result.isOK()) {
				return request.createResponseBuilder(
						HttpStatus.valueOf(result.error()))
						.build();
			}

			return request.createResponseBuilder(HttpStatus.OK)
					.header(
							"Set-Cookie",
							"cc:session=" + result.value()
									+ "; Path=/; Max-Age=3600; HttpOnly")
					.build();

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid login JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("user-update")
	public HttpResponseMessage updateUser(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.PUT }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user/{id}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String path = request.getUri().getPath();
		String[] parts = path.split("/");
		String id = parts[parts.length - 1];

		context.getLogger().info("PUT /user/" + id);

		try {
			User user = body(request, User.class);

			Result<User> result = UserOps.getInstance().update(
					sessionId(request),
					id,
					user);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid user JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("user-auctions")
	public HttpResponseMessage userAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user/{id}/auctions") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String path = request.getUri().getPath();
		String[] parts = path.split("/");
		String id = parts[parts.length - 2];

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);
		String status = request.getQueryParameters()
				.getOrDefault("status", "");

		context.getLogger().info(
				"GET /user/" + id + "/auctions");

		Result<Auction[]> result = UserOps.getInstance().auctions(
				id,
				status,
				offset,
				limit);

		return response(request, result);
	}

	@FunctionName("user-delete")
	public HttpResponseMessage deleteUser(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.DELETE }, authLevel = AuthorizationLevel.ANONYMOUS, route = "user/{id}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String path = request.getUri().getPath();
		String[] parts = path.split("/");
		String id = parts[parts.length - 1];

		context.getLogger().info("DELETE /user/" + id);

		Result<Void> result = UserOps.getInstance().delete(
				sessionId(request),
				id);

		if (result.isOK()) {
			return request.createResponseBuilder(HttpStatus.OK)
					.build();
		}

		return request.createResponseBuilder(
				HttpStatus.valueOf(result.error()))
				.build();
	}

	@FunctionName("auction-create")
	public HttpResponseMessage createAuction(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		try {
			Auction auction = body(request, Auction.class);

			Result<Auction> result = AuctionOps.getInstance()
					.create(sessionId(request), auction);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid auction JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("auction-get")
	public HttpResponseMessage auctionGet(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{id:guid}") HttpRequestMessage<Optional<String>> request,
			ExecutionContext context) {

		String id = pathPart(request, 1);

		return response(
				request,
				AuctionOps.getInstance().get(id));
	}

	@FunctionName("auction-update")
	public HttpResponseMessage updateAuction(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.PUT }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{id:guid}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String id = pathPart(request, 1);

		try {
			Auction auction = body(request, Auction.class);

			Result<Auction> result = AuctionOps.getInstance()
					.update(
							sessionId(request),
							id,
							auction);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid auction JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("auction-list")
	public HttpResponseMessage listAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Auction[]> result = AuctionOps.getInstance()
				.list(offset, limit);

		return response(request, result);
	}

	@FunctionName("auction-recent")
	public HttpResponseMessage recentAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/any/recent") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Auction[]> result = AuctionOps.getInstance()
				.recent(offset, limit);

		return response(request, result);
	}

	@FunctionName("auction-about-to-close")
	public HttpResponseMessage aboutToCloseAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/any/about-to-close") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Auction[]> result = AuctionOps.getInstance()
				.aboutToClose(offset, limit);

		return response(request, result);
	}

	@FunctionName("auction-popular")
	public HttpResponseMessage popularAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/any/popular") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Auction[]> result = AuctionOps.getInstance()
				.popular(offset, limit);

		return response(request, result);
	}

	@FunctionName("auction-search")
	public HttpResponseMessage searchAuctions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/search") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String query = queryString(request, "q", "");

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Auction[]> result = AuctionOps.getInstance()
				.search(query, offset, limit);

		return response(request, result);
	}

	@FunctionName("bid-create")
	public HttpResponseMessage createBid(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/bid") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 2);

		try {
			Bid bid = body(request, Bid.class);

			Result<Bid> result = BidOps.getInstance()
					.create(
							sessionId(request),
							auctionId,
							bid);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid bid JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("bid-list")
	public HttpResponseMessage listBids(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/bid") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 2);

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Bid[]> result = BidOps.getInstance()
				.list(
						auctionId,
						offset,
						limit);

		return response(request, result);
	}

	@FunctionName("bid-get")
	public HttpResponseMessage getBid(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/bid/{bidId}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 3);
		String bidId = pathPart(request, 1);

		Result<Bid> result = BidOps.getInstance()
				.get(auctionId, bidId);

		return response(request, result);
	}

	@FunctionName("question-ask")
	public HttpResponseMessage askQuestion(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/question") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 2);

		try {
			Question question = body(request, Question.class);

			Result<Question> result = QuestionOps.getInstance()
					.ask(auctionId, question);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid question JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("question-list")
	public HttpResponseMessage listQuestions(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/question") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 2);

		int offset = queryInt(request, "st", 0);
		int limit = queryInt(request, "len", 20);

		Result<Question[]> result = QuestionOps.getInstance()
				.list(
						auctionId,
						offset,
						limit);

		return response(request, result);
	}

	@FunctionName("question-reply")
	public HttpResponseMessage replyQuestion(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "auction/{auctionId}/question/{questionId}/reply") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String auctionId = pathPart(request, 4);
		String questionId = pathPart(request, 2);

		try {
			Question question = body(request, Question.class);

			Result<Question> result = QuestionOps.getInstance()
					.reply(
							sessionId(request),
							auctionId,
							questionId,
							question);

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().warning(
					"Invalid question reply JSON: " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.BAD_REQUEST)
					.build();
		}
	}

	@FunctionName("media-upload")
	public HttpResponseMessage uploadMedia(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, dataType = "binary", route = "media") HttpRequestMessage<byte[]> request,
			final ExecutionContext context) {

		try {
			String contentType = request.getHeaders()
					.get("Content-Type");

			if (contentType == null)
				contentType = request.getHeaders()
						.get("content-type");

			byte[] contents = request.getBody();

			context.getLogger().info(
					"Media upload: contentType=" + contentType
							+ ", body="
							+ (contents == null
									? "null"
									: contents.length + " bytes"));

			Result<String> result = MediaOps.getInstance()
					.upload(contentType, contents);

			context.getLogger().info(
					"Media upload result: "
							+ (result.isOK()
									? "OK"
									: "ERROR " + result.error()));

			return response(request, result);

		} catch (Exception e) {
			context.getLogger().severe(
					"Media upload exception: "
							+ e.getClass().getName()
							+ ": " + e.getMessage());

			return request.createResponseBuilder(
					HttpStatus.INTERNAL_SERVER_ERROR)
					.body(
							e.getClass().getName()
									+ ": " + e.getMessage())
					.build();
		}
	}

	@FunctionName("media-download")
	public HttpResponseMessage downloadMedia(
			@HttpTrigger(name = "req", methods = {
					HttpMethod.GET }, authLevel = AuthorizationLevel.ANONYMOUS, route = "media/{id}") HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		String id = pathPart(request, 1);

		Result<MediaDAO> result = MediaOps.getInstance()
				.download(id);

		if (!result.isOK())
			return response(request, result);

		MediaDAO media = result.value();

		return request.createResponseBuilder(HttpStatus.OK)
				.header(
						"Content-Type",
						media.getContentType())
				.body(media.decoded())
				.build();
	}
}