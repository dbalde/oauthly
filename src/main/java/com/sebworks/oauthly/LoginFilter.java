package com.sebworks.oauthly;

import com.sebworks.oauthly.controller.OAuthAuthorizationController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * This filter is responsible for protecting authorization server endpoint, such as authorize and profile
 * It redirects to login page if no session was found.
 *
 * @author Selim Eren Bekçe
 *
 */
public class LoginFilter implements Filter{

	public static final String PROTECTED_URL_PATTERN = "protectedURLPattern";
	private static final Logger logger = LoggerFactory.getLogger(LoginFilter.class);
	
	private Pattern pattern;
	private OAuthAuthorizationController controller;
	private WebApplicationContext context;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		context = WebApplicationContextUtils.getWebApplicationContext(filterConfig.getServletContext());
		if(context == null){
			throw new ServletException("Spring context not found");
		}
		controller = context.getBean(OAuthAuthorizationController.class);

		String protectedURLPattern = filterConfig.getInitParameter(PROTECTED_URL_PATTERN);
		if(protectedURLPattern == null){
			throw new ServletException("LoginFilter requires a "+PROTECTED_URL_PATTERN+" init parameter to work.");
		}
		try {
			pattern = Pattern.compile(protectedURLPattern);
		} catch (Exception e) {
			throw new ServletException(e.getMessage(), e);
		}
		logger.info("Initialized LoginFilter");
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException,
			ServletException {

		HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

		// Protected pattern does not match: allow
		String requestUri = request.getRequestURI().replace(request.getContextPath(), "");
		if (!pattern.matcher(requestUri).matches()) {
			chain.doFilter(request, response);
			return;
		}

		SessionData sessionData = context.getBean(SessionData.class);
		if(sessionData.getUserId() != null){
			chain.doFilter(request, response);
			return;
		}

		//save current url to a session param
		request.getSession().setAttribute("redir", getFullURL(request));

		//redirect to login
		response.sendRedirect("/login");
	}

	@Override
	public void destroy() {
	}

	public static String getFullURL(HttpServletRequest request) {
		StringBuffer requestURL = request.getRequestURL();
		String queryString = request.getQueryString();

		if (queryString == null) {
			return requestURL.toString();
		} else {
			return requestURL.append('?').append(queryString).toString();
		}
	}
}