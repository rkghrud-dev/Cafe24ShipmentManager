"""
Cafe24 OAuth2 Access Token 발급 스크립트
1) 브라우저에서 인증
2) 콜백 서버(8080)로 code 자동 수신 또는 수동 입력
3) access_token, refresh_token을 공유 JSON 파일에 저장
"""
import base64
import json
import os
import threading
import urllib.parse
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

import requests

SCOPES = ",".join([
    "mall.read_order",
    "mall.write_order",
    "mall.read_shipping",
    "mall.write_shipping",
    "mall.read_product",
    "mall.write_product",
])
SHARED_TOKEN_PATH = os.path.join(os.path.expanduser("~"), "Desktop", "key", "cafe24_token.json")

callback_event = threading.Event()
callback_data = {"code": None, "error": None}


def load_shared_config():
    cfg = {
        "MallId": "rkghrud1",
        "ClientId": "CfoSdFlVT6oDL1VUWN9jwB",
        "ClientSecret": "xqpSFOC2ENgmKV4IYT5AYC",
        "AccessToken": "",
        "RefreshToken": "",
        "RedirectUri": "https://e28a-14-6-135-151.ngrok-free.app/oauth/callback",
        "ApiVersion": "2025-12-01",
        "ShopNo": "1",
        "Scope": SCOPES,
    }

    if os.path.isfile(SHARED_TOKEN_PATH):
        try:
            with open(SHARED_TOKEN_PATH, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                for key in cfg:
                    value = str(loaded.get(key, "") or "").strip()
                    if value:
                        cfg[key] = value
        except Exception as exc:
            print(f"공유 토큰 파일 로드 실패: {exc}")

    env_redirect = os.getenv("CAFE24_REDIRECT_URI", "").strip()
    if env_redirect:
        cfg["RedirectUri"] = env_redirect

    if not cfg["Scope"]:
        cfg["Scope"] = SCOPES

    return cfg


def save_shared_config(cfg, access_token, refresh_token):
    payload = dict(cfg)
    payload["AccessToken"] = access_token
    payload["RefreshToken"] = refresh_token
    payload["UpdatedAt"] = datetime.now().isoformat(timespec="seconds")

    os.makedirs(os.path.dirname(SHARED_TOKEN_PATH), exist_ok=True)
    with open(SHARED_TOKEN_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)


class CallbackHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/oauth/callback":
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Not Found")
            return

        params = urllib.parse.parse_qs(parsed.query)
        callback_data["code"] = params.get("code", [None])[0]
        callback_data["error"] = params.get("error", [None])[0]

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write((
            "<html><body><h2>인증 완료</h2>"
            "<p>이 창을 닫고 터미널로 돌아가세요.</p>"
            "</body></html>"
        ).encode("utf-8"))
        callback_event.set()

    def log_message(self, format, *args):
        return


def start_callback_server():
    server = HTTPServer(("localhost", 8080), CallbackHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def extract_code(value):
    parsed = urllib.parse.urlparse(value)
    params = urllib.parse.parse_qs(parsed.query)
    if "code" not in params:
        return value
    return params["code"][0]


def exchange_token(cfg, code):
    token_url = f"https://{cfg['MallId']}.cafe24api.com/api/v2/oauth/token"
    auth_header = base64.b64encode(f"{cfg['ClientId']}:{cfg['ClientSecret']}".encode()).decode()

    resp = requests.post(
        token_url,
        headers={
            "Authorization": f"Basic {auth_header}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        data={
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": cfg["RedirectUri"],
        },
    )

    if resp.status_code >= 400:
        try:
            first_result = resp.json()
        except Exception:
            first_result = {}

        if first_result.get("error_description") == "Invalid client_secret":
            print("\n토큰 교환 재시도: client_id/client_secret 본문 전송 방식")
            resp = requests.post(
                token_url,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                data={
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": cfg["RedirectUri"],
                    "client_id": cfg["ClientId"],
                    "client_secret": cfg["ClientSecret"],
                },
            )

    return resp


def main():
    cfg = load_shared_config()
    server = None
    try:
        server = start_callback_server()
    except OSError as exc:
        print(f"콜백 서버(8080) 시작 실패: {exc}")
        print("수동 입력 모드로 진행합니다.")

    auth_url = (
        f"https://{cfg['MallId']}.cafe24api.com/api/v2/oauth/authorize"
        f"?response_type=code"
        f"&client_id={cfg['ClientId']}"
        f"&state=shipment_manager"
        f"&redirect_uri={urllib.parse.quote(cfg['RedirectUri'])}"
        f"&scope={cfg['Scope']}"
    )

    print("=" * 60)
    print("브라우저에서 Cafe24 인증 페이지가 열립니다.")
    print("인증 후 코드를 자동 수신합니다. 실패 시 수동 입력으로 진행합니다.")
    print("=" * 60)
    print(f"공유 토큰 파일: {SHARED_TOKEN_PATH}")
    print(f"사용 Redirect URI: {cfg['RedirectUri']}")
    print(f"\n인증 URL:\n{auth_url}\n")

    webbrowser.open(auth_url)

    if server:
        print("-" * 60)
        print("인증 후 콜백을 기다리는 중입니다... (최대 180초)")
        if callback_event.wait(timeout=180):
            if callback_data["error"]:
                print(f"\n인증 오류: {callback_data['error']}")
                return
            code = callback_data["code"]
            if not code:
                print("\n콜백에서 code를 받지 못했습니다.")
                return
            print("\n콜백으로 인증 코드를 수신했습니다.")
        else:
            print("\n자동 수신 시간 초과. 수동 입력 모드로 전환합니다.")
            redirected_value = input("리다이렉트된 URL 또는 code를 붙여넣으세요: ").strip()
            code = extract_code(redirected_value)
    else:
        print("-" * 60)
        redirected_value = input("리다이렉트된 URL 또는 code를 붙여넣으세요: ").strip()
        code = extract_code(redirected_value)

    print(f"\n인증 코드: {code}")

    resp = exchange_token(cfg, code)

    print(f"\n응답 코드: {resp.status_code}")
    result = resp.json()
    print(json.dumps(result, indent=2, ensure_ascii=False))

    if "access_token" in result:
        access_token = result["access_token"]
        refresh_token = result.get("refresh_token", "")
        try:
            save_shared_config(cfg, access_token, refresh_token)
            print(f"\n공유 토큰 파일 업데이트 완료: {SHARED_TOKEN_PATH}")
        except Exception as exc:
            print(f"\n공유 토큰 파일 업데이트 실패: {exc}")

        print(f"\n{'='*60}")
        print(f"ACCESS_TOKEN  = {access_token}")
        print(f"REFRESH_TOKEN = {refresh_token}")
        print(f"{'='*60}")
    else:
        print("\n토큰 발급 실패. 위 응답을 확인하세요.")

    if server:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()