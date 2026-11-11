<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FOS · Sign in</title><link rel="stylesheet" href="<c:url value='/admin/assets/style.css'/>"></head>
<body class="login-body"><main class="login-card"><div class="brand-mark">F</div><p class="eyebrow">FOS / OPERATIONS</p><h1>Welcome back.</h1><p class="muted">Sign in to manage integrations and test message flows.</p>
<c:if test="${param.error != null}"><p class="notice" role="alert">Sign-in failed. Check your username and password.</p></c:if>
<c:if test="${param.logout != null}"><p class="notice">You have signed out.</p></c:if>
<form method="post" action="<c:url value='/admin/login'/>"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><label>Username<input name="username" autocomplete="username" required autofocus></label><label>Password<input name="password" type="password" autocomplete="current-password" required></label><button class="primary" type="submit">Sign in</button></form><p class="fine">Authorized operations personnel only.</p></main></body></html>
