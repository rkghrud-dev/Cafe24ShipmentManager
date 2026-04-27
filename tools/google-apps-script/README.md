# Cafe24 Google Apps Script 토큰 관리자

이 폴더의 `cafe24-token-manager.gs`를 Google Apps Script에 붙여넣으면 서버 없이 Cafe24 토큰을 중앙 갱신할 수 있습니다.

## 구조

- `Cafe24Auth`: 최초 인증/2주 재인증 때만 사용합니다.
- Google Apps Script: `RefreshToken`을 보관하고 30분마다 `AccessToken`을 갱신합니다.
- 데스크탑/안드로이드: `TokenProviderUrl`에서 최신 `AccessToken`만 받아 Cafe24 API를 호출합니다.

## 최초 설정

1. `Cafe24Auth`로 각 몰을 인증해서 `cafe24_token_몰ID.json`을 만듭니다.
2. Google Apps Script 새 프로젝트를 만들고 `cafe24-token-manager.gs` 내용을 `Code.gs`에 붙여넣습니다.
3. `SETUP_SECRET`을 긴 임의 문자열로 바꿉니다.
4. `SETUP_TOKENS`에 Cafe24Auth JSON에서 `MallId`, `ClientId`, `ClientSecret`, `RefreshToken`, `RedirectUri`, `Scope`, `ApiVersion`, `ShopNo`, `UpdatedAt`, `RefreshTokenUpdatedAt` 값을 넣습니다.
5. `setupTokenManager()`를 한 번 실행하고 권한을 승인합니다.
6. 배포 > 새 배포 > 웹 앱으로 배포합니다. 실행 권한은 본인, 액세스는 링크가 있는 사용자로 둡니다.
7. 배포된 `/exec` URL을 `TokenProviderUrl`로 사용하고, `SETUP_SECRET` 값을 `TokenProviderKey`로 사용합니다.

## 앱에 넣는 JSON 예시

`cafe24-provider-import.example.json` 형식으로 몰별 JSON을 만들어 안드로이드에 가져오거나, 데스크탑 `appsettings.json`의 Cafe24 Markets에 넣습니다.

```json
{
  "MallId": "rkghrud1",
  "TokenProviderUrl": "https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec",
  "TokenProviderKey": "CHANGE_ME_TO_LONG_RANDOM_TEXT",
  "ApiVersion": "2025-12-01",
  "ShopNo": "1"
}
```

## 재인증 주기

Apps Script가 `RefreshToken`까지 새 값으로 저장하므로 2시간마다 아무것도 할 필요가 없습니다. 다만 Cafe24 refresh token 자체가 만료되거나 갱신 실패가 나면 `Cafe24Auth`로 다시 인증하고 `SETUP_TOKENS` 또는 Script Properties의 해당 몰 토큰을 새 값으로 업데이트한 뒤 `setupTokenManager()`를 다시 실행하면 됩니다.
