// Cafe24 token manager for Google Apps Script.
// 1. Paste this file into https://script.google.com as Code.gs.
// 2. Fill SETUP_SECRET and SETUP_TOKENS.
// 3. Run setupTokenManager() once and approve permissions.
// 4. Deploy as Web app, then use the /exec URL as TokenProviderUrl.

const SETUP_SECRET = 'CHANGE_ME_TO_LONG_RANDOM_TEXT';
const REFRESH_WINDOW_MINUTES = 45;

const SETUP_TOKENS = {
  // Example:
  // rkghrud1: {
  //   MallId: 'rkghrud1',
  //   ClientId: '...',
  //   ClientSecret: '...',
  //   RefreshToken: '...',
  //   AccessToken: '',
  //   RedirectUri: '...',
  //   Scope: '...',
  //   ApiVersion: '2025-12-01',
  //   ShopNo: '1',
  //   UpdatedAt: '2026-04-27T00:00:00+09:00',
  //   RefreshTokenUpdatedAt: '2026-04-27T00:00:00+09:00'
  // }
};

function setupTokenManager() {
  const props = PropertiesService.getScriptProperties();
  props.setProperty('APP_SECRET', SETUP_SECRET);

  Object.keys(SETUP_TOKENS).forEach((mallId) => {
    const token = SETUP_TOKENS[mallId];
    token.MallId = token.MallId || mallId;
    saveToken_(token.MallId, token);
  });

  ScriptApp.getProjectTriggers()
    .filter((trigger) => trigger.getHandlerFunction() === 'refreshDueTokens')
    .forEach((trigger) => ScriptApp.deleteTrigger(trigger));

  ScriptApp.newTrigger('refreshDueTokens')
    .timeBased()
    .everyMinutes(30)
    .create();

  refreshDueTokens();
}

function doGet(e) {
  try {
    const params = (e && e.parameter) || {};
    assertSecret_(params.key || params.secret || '');

    const mallId = String(params.mall || params.mallId || '').trim();
    if (!mallId) throw new Error('mall parameter is required');

    const token = refreshMallIfDue_(mallId, REFRESH_WINDOW_MINUTES);
    return json_({
      ok: true,
      MallId: token.MallId,
      AccessToken: token.AccessToken,
      ApiVersion: token.ApiVersion || '2025-12-01',
      ShopNo: token.ShopNo || '1',
      UpdatedAt: token.UpdatedAt || '',
      AccessTokenExpiresAt: addHours_(token.UpdatedAt, 2)
    });
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function refreshDueTokens() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(25000)) return;

  try {
    const props = PropertiesService.getScriptProperties();
    const mallIds = JSON.parse(props.getProperty('MALL_IDS') || '[]');
    mallIds.forEach((mallId) => refreshMallIfDue_(mallId, REFRESH_WINDOW_MINUTES));
  } finally {
    lock.releaseLock();
  }
}

function refreshMallIfDue_(mallId, refreshWindowMinutes) {
  const token = loadToken_(mallId);
  if (!token) throw new Error('token not found for mall: ' + mallId);

  const accessExpiry = addHours_(token.UpdatedAt, 2);
  const now = new Date();
  if (accessExpiry && accessExpiry.getTime() - now.getTime() > refreshWindowMinutes * 60 * 1000 && token.AccessToken) {
    return token;
  }

  return refreshAccessToken_(token);
}

function refreshAccessToken_(existing) {
  if (!existing.MallId) throw new Error('MallId is missing');
  if (!existing.ClientId || !existing.ClientSecret) throw new Error('ClientId/ClientSecret is missing: ' + existing.MallId);
  if (!existing.RefreshToken) throw new Error('RefreshToken is missing: ' + existing.MallId);

  const url = 'https://' + existing.MallId + '.cafe24api.com/api/v2/oauth/token';
  const basic = Utilities.base64Encode(existing.ClientId + ':' + existing.ClientSecret);
  const response = UrlFetchApp.fetch(url, {
    method: 'post',
    headers: { Authorization: 'Basic ' + basic },
    payload: {
      grant_type: 'refresh_token',
      refresh_token: existing.RefreshToken
    },
    muteHttpExceptions: true
  });

  const status = response.getResponseCode();
  const body = response.getContentText();
  if (status < 200 || status >= 300) {
    throw new Error('Cafe24 refresh failed for ' + existing.MallId + ': HTTP ' + status + ' ' + body);
  }

  const payload = JSON.parse(body);
  const now = new Date().toISOString();
  const next = Object.assign({}, existing, {
    AccessToken: payload.access_token || '',
    RefreshToken: payload.refresh_token || existing.RefreshToken,
    Scope: payload.scope || existing.Scope || '',
    ApiVersion: existing.ApiVersion || '2025-12-01',
    ShopNo: existing.ShopNo || '1',
    UpdatedAt: now
  });

  if (payload.refresh_token && payload.refresh_token !== existing.RefreshToken) {
    next.RefreshTokenUpdatedAt = now;
  } else {
    next.RefreshTokenUpdatedAt = existing.RefreshTokenUpdatedAt || existing.UpdatedAt || now;
  }

  saveToken_(next.MallId, next);
  return next;
}

function loadToken_(mallId) {
  const raw = PropertiesService.getScriptProperties().getProperty('TOKEN_' + mallId);
  return raw ? JSON.parse(raw) : null;
}

function saveToken_(mallId, token) {
  const props = PropertiesService.getScriptProperties();
  props.setProperty('TOKEN_' + mallId, JSON.stringify(token));

  const mallIds = new Set(JSON.parse(props.getProperty('MALL_IDS') || '[]'));
  mallIds.add(mallId);
  props.setProperty('MALL_IDS', JSON.stringify(Array.from(mallIds)));
}

function assertSecret_(secret) {
  const expected = PropertiesService.getScriptProperties().getProperty('APP_SECRET');
  if (!expected || secret !== expected) throw new Error('unauthorized');
}

function json_(value) {
  return ContentService
    .createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}

function addHours_(value, hours) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return new Date(date.getTime() + hours * 60 * 60 * 1000);
}
