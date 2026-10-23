package devices_test

import (
	"github.com/smsgate/backend/internal/config"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/apptest"
)

func ck(c *http.Cookie) []string { return []string{"Cookie", c.Name + "=" + c.Value} }
func bearer(tok string) []string { return []string{"Authorization", "Bearer " + tok} }

func newCode(t *testing.T, a *apptest.App, c *http.Cookie) string {
	t.Helper()
	r, m := a.Do("POST", "/app/pairing-codes", nil, ck(c)...)
	if r.StatusCode != 201 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	return m["code"].(string)
}

func reg(code string) map[string]any {
	return map[string]any{"pairing_code": code, "name": "Phone", "sims": []map[string]any{{"slot": 0, "subscription_id": 1, "carrier": "X"}}, "app_version": "1.0"}
}

func TestPairWithCode(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.PublicURL = "https://sms.example.com/gw" })
	c := a.Signup(t, "a@example.com")
	r, m := a.Do("POST", "/app/pairing-codes", nil, ck(c)...)
	if r.StatusCode != 201 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	code := m["code"].(string)
	if len(code) != 8 || m["expires_at"] == nil || m["qr_payload"] != "smsgate://pair?server=https%3A%2F%2Fsms.example.com%2Fgw&code="+code {
		t.Fatalf("%v", m)
	}
	// lowercase + whitespace input is normalised
	r, m = a.Do("POST", "/device/v1/register", reg(" "+strings.ToLower(code)+" "))
	if r.StatusCode != 201 || m["device_id"] == nil || !strings.HasPrefix(m["device_token"].(string), "dt_") {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	_, l := a.Do("GET", "/app/devices", nil, ck(c)...)
	d := l["data"].([]any)
	if len(d) != 1 || d[0].(map[string]any)["name"] != "Phone" {
		t.Fatalf("%v", l)
	}
}

func TestPairingCodeRequiresVerifiedEmail(t *testing.T) {
	a := apptest.New(t)
	a.Do("POST", "/app/signup", map[string]string{"email": "u@example.com", "password": apptest.Password})
	r, _ := a.Do("POST", "/app/login", map[string]string{"email": "u@example.com", "password": apptest.Password})
	var c *http.Cookie
	for _, x := range r.Cookies() {
		c = x
	}
	r, m := a.Do("POST", "/app/pairing-codes", nil, ck(c)...)
	if r.StatusCode != 403 || m["error"].(map[string]any)["code"] != "email_not_verified" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestPairingCodeSingleUse(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	code := newCode(t, a, c)
	if r, m := a.Do("POST", "/device/v1/register", reg(code)); r.StatusCode != 201 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	r, m := a.Do("POST", "/device/v1/register", reg(code))
	if r.StatusCode != 400 || m["error"].(map[string]any)["code"] != "invalid_pairing_code" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestPairingCodeExpired(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	code := newCode(t, a, c)
	a.Advance(6 * time.Minute)
	r, m := a.Do("POST", "/device/v1/register", reg(code))
	if r.StatusCode != 400 || m["error"].(map[string]any)["code"] != "invalid_pairing_code" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestRegisterWithLogin(t *testing.T) {
	a := apptest.New(t)
	a.Signup(t, "a@example.com")
	b := reg("")
	delete(b, "pairing_code")
	b["email"], b["password"] = "A@example.com", apptest.Password
	if r, m := a.Do("POST", "/device/v1/register", b); r.StatusCode != 201 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	b["password"] = "wrong-password-here"
	if r, _ := a.Do("POST", "/device/v1/register", b); r.StatusCode != 401 {
		t.Fatalf("wrong pw: %d", r.StatusCode)
	}
	b["email"] = "nobody@example.com"
	if r, _ := a.Do("POST", "/device/v1/register", b); r.StatusCode != 401 {
		t.Fatalf("unknown: %d", r.StatusCode)
	}
	a.Do("POST", "/app/signup", map[string]string{"email": "u@example.com", "password": apptest.Password})
	b["email"], b["password"] = "u@example.com", apptest.Password
	r, m := a.Do("POST", "/device/v1/register", b)
	if r.StatusCode != 403 || m["error"].(map[string]any)["code"] != "email_not_verified" {
		t.Fatalf("unverified: %d %v", r.StatusCode, m)
	}
}

func TestRegisterValidation(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	sim := func(slot int) map[string]any {
		return map[string]any{"slot": slot, "subscription_id": 1, "carrier": "X"}
	}
	cases := []struct {
		name string
		mut  func(m map[string]any)
		code string
	}{
		{"both creds", func(m map[string]any) { m["email"], m["password"] = "a@example.com", "x" }, "invalid_credentials_input"},
		{"no creds", func(m map[string]any) { delete(m, "pairing_code") }, "invalid_credentials_input"},
		{"email only", func(m map[string]any) { delete(m, "pairing_code"); m["email"] = "a@example.com" }, "invalid_credentials_input"},
		{"empty name", func(m map[string]any) { m["name"] = "  " }, "invalid_name"},
		{"long name", func(m map[string]any) { m["name"] = strings.Repeat("n", 65) }, "invalid_name"},
		{"dup slot", func(m map[string]any) { m["sims"] = []any{sim(1), sim(1)} }, "invalid_sims"},
		{"bad slot", func(m map[string]any) { m["sims"] = []any{sim(4)} }, "invalid_sims"},
		{"5 sims", func(m map[string]any) { m["sims"] = []any{sim(0), sim(1), sim(2), sim(3), sim(0)} }, "invalid_sims"},
		{"long version", func(m map[string]any) { m["app_version"] = strings.Repeat("v", 33) }, "invalid_app_version"},
	}
	for _, tc := range cases {
		b := reg(newCode(t, a, c))
		tc.mut(b)
		r, m := a.Do("POST", "/device/v1/register", b)
		if r.StatusCode != 422 || m["error"].(map[string]any)["code"] != tc.code {
			t.Errorf("%s: %d %v", tc.name, r.StatusCode, m)
		}
	}
	// a failed validation must not burn the code
	code := newCode(t, a, c)
	bad := reg(code)
	bad["name"] = ""
	a.Do("POST", "/device/v1/register", bad)
	if r, m := a.Do("POST", "/device/v1/register", reg(code)); r.StatusCode != 201 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestHeartbeatMarksOnline(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	key := a.APIKey(t, c)
	id, tok := a.PairDevice(t, c)
	list := func() map[string]any {
		r, m := a.Do("GET", "/v1/devices", nil, bearer(key)...)
		if r.StatusCode != 200 {
			t.Fatalf("%d %v", r.StatusCode, m)
		}
		d := m["data"].([]any)
		if len(d) != 1 {
			t.Fatalf("%v", m)
		}
		return d[0].(map[string]any)
	}
	if d := list(); d["online"] != false || d["id"] != id.String() || d["battery"] != nil {
		t.Fatalf("%v", d)
	}
	r, m := a.Do("POST", "/device/v1/heartbeat", map[string]any{"battery": 80, "charging": true, "signal": 3, "app_version": "1.1"}, bearer(tok)...)
	if r.StatusCode != 200 || m["server_time"] == nil {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if _, err := time.Parse(time.RFC3339Nano, m["server_time"].(string)); err != nil {
		t.Fatal(err)
	}
	d := list()
	if d["online"] != true || d["battery"] != float64(80) || d["charging"] != true || d["last_seen_at"] == nil {
		t.Fatalf("%v", d)
	}
	if s := d["sims"].([]any); len(s) != 1 || s[0].(map[string]any)["carrier"] != "Test" {
		t.Fatalf("sims kept: %v", d["sims"])
	}
	a.Advance(3 * time.Minute)
	if d := list(); d["online"] != false {
		t.Fatalf("should be offline: %v", d)
	}
	// heartbeat with sims replaces them; null signal allowed
	a.Do("POST", "/device/v1/heartbeat", map[string]any{"battery": 5, "signal": nil, "sims": []map[string]any{{"slot": 1, "subscription_id": 2, "carrier": "Z"}}}, bearer(tok)...)
	d = list()
	if s := d["sims"].([]any); d["online"] != true || len(s) != 1 || s[0].(map[string]any)["slot"] != float64(1) {
		t.Fatalf("%v", d)
	}
}

func TestHeartbeatValidation(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	_, tok := a.PairDevice(t, c)
	for _, b := range []map[string]any{{"battery": 101}, {"battery": -1}, {"signal": 5}, {"signal": -1}, {"sims": []map[string]any{{"slot": 9}}}} {
		r, m := a.Do("POST", "/device/v1/heartbeat", b, bearer(tok)...)
		if r.StatusCode != 422 {
			t.Errorf("%v: %d %v", b, r.StatusCode, m)
		}
	}
}

func TestFCMToken(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	id, tok := a.PairDevice(t, c)
	if r, m := a.Do("PUT", "/device/v1/fcm-token", map[string]string{"fcm_token": "new"}, bearer(tok)...); r.StatusCode != 204 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	var got string
	if err := a.Pool.QueryRow(t.Context(), `SELECT fcm_token FROM devices WHERE id=$1`, id).Scan(&got); err != nil || got != "new" {
		t.Fatalf("%q %v", got, err)
	}
	if r, _ := a.Do("PUT", "/device/v1/fcm-token", map[string]string{"fcm_token": ""}, bearer(tok)...); r.StatusCode != 422 {
		t.Fatalf("empty: %d", r.StatusCode)
	}
}

func TestDeviceAuthRejections(t *testing.T) {
	a := apptest.New(t)
	for _, h := range [][]string{nil, {"Authorization", "Bearer dt_nope"}, {"Authorization", "Bearer sk_live_x"}, {"Authorization", "dt_x"}} {
		if r, _ := a.Do("POST", "/device/v1/heartbeat", map[string]any{}, h...); r.StatusCode != 401 {
			t.Fatalf("%v: %d", h, r.StatusCode)
		}
	}
	c := a.Signup(t, "a@example.com")
	_, tok := a.PairDevice(t, c)
	if r, _ := a.Do("POST", "/device/v1/heartbeat", map[string]any{}, "Authorization", "bEaReR "+tok); r.StatusCode != 200 {
		t.Fatalf("case-insensitive scheme: %d", r.StatusCode)
	}
}

func TestRevokedDeviceTokenRejected(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	key := a.APIKey(t, c)
	id, tok := a.PairDevice(t, c)
	if r, _ := a.Do("POST", "/device/v1/heartbeat", map[string]any{"battery": 50}, bearer(tok)...); r.StatusCode != 200 {
		t.Fatalf("pre: %d", r.StatusCode)
	}
	if r, m := a.Do("DELETE", "/app/devices/"+id.String(), nil, ck(c)...); r.StatusCode != 204 {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if r, _ := a.Do("POST", "/device/v1/heartbeat", map[string]any{"battery": 50}, bearer(tok)...); r.StatusCode != 401 {
		t.Fatalf("post-revoke heartbeat: %d", r.StatusCode)
	}
	if r, _ := a.Do("PUT", "/device/v1/fcm-token", map[string]string{"fcm_token": "x"}, bearer(tok)...); r.StatusCode != 401 {
		t.Fatalf("post-revoke fcm: %d", r.StatusCode)
	}
	var fcm *string
	var rev *time.Time
	if err := a.Pool.QueryRow(t.Context(), `SELECT fcm_token, revoked_at FROM devices WHERE id=$1`, id).Scan(&fcm, &rev); err != nil || fcm != nil || rev == nil {
		t.Fatalf("%v %v %v", fcm, rev, err)
	}
	_, l := a.Do("GET", "/app/devices", nil, ck(c)...)
	_, l2 := a.Do("GET", "/v1/devices", nil, bearer(key)...)
	if len(l["data"].([]any)) != 0 || len(l2["data"].([]any)) != 0 {
		t.Fatalf("%v %v", l, l2)
	}
	if r, m := a.Do("DELETE", "/app/devices/"+id.String(), nil, ck(c)...); r.StatusCode != 404 {
		t.Fatalf("double revoke: %d %v", r.StatusCode, m)
	}
	if r, _ := a.Do("PATCH", "/app/devices/"+id.String(), map[string]string{"name": "x"}, ck(c)...); r.StatusCode != 404 {
		t.Fatalf("rename revoked: %d", r.StatusCode)
	}
}

func TestRename(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	id, _ := a.PairDevice(t, c)
	r, m := a.Do("PATCH", "/app/devices/"+id.String(), map[string]string{"name": " New Name "}, ck(c)...)
	if r.StatusCode != 200 || m["name"] != "New Name" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if r, m := a.Do("PATCH", "/app/devices/"+id.String(), map[string]string{"name": ""}, ck(c)...); r.StatusCode != 422 || m["error"].(map[string]any)["code"] != "invalid_name" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if r, _ := a.Do("PATCH", "/app/devices/not-a-uuid", map[string]string{"name": "x"}, ck(c)...); r.StatusCode != 404 {
		t.Fatalf("%d", r.StatusCode)
	}
}

func TestDevicesScopedToUser(t *testing.T) {
	a := apptest.New(t)
	ca := a.Signup(t, "a@example.com")
	cb := a.Signup(t, "b@example.com")
	keyB := a.APIKey(t, cb)
	id, _ := a.PairDevice(t, ca)
	_, l := a.Do("GET", "/v1/devices", nil, bearer(keyB)...)
	if d, ok := l["data"].([]any); !ok || len(d) != 0 {
		t.Fatalf("%v", l)
	}
	_, l = a.Do("GET", "/app/devices", nil, ck(cb)...)
	if d, ok := l["data"].([]any); !ok || len(d) != 0 {
		t.Fatalf("%v", l)
	}
	if r, m := a.Do("PATCH", "/app/devices/"+id.String(), map[string]string{"name": "hax"}, ck(cb)...); r.StatusCode != 404 || m["error"].(map[string]any)["code"] != "device_not_found" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
	if r, _ := a.Do("DELETE", "/app/devices/"+id.String(), nil, ck(cb)...); r.StatusCode != 404 {
		t.Fatalf("%d", r.StatusCode)
	}
}

func TestRegisterLoginNoPoolDeadlock(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.DBMaxConns = 2 })
	a.Signup(t, "a@example.com")
	codes := make([]int, 7)
	var wg sync.WaitGroup
	for i := range codes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			pw := "wrong-password-here"
			if i == 6 {
				pw = apptest.Password
			}
			b := reg("")
			delete(b, "pairing_code")
			b["email"], b["password"] = "a@example.com", pw
			r, _ := a.Do("POST", "/device/v1/register", b)
			codes[i] = r.StatusCode
		}()
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		t.Fatal("register deadlocked")
	}
	for i, c := range codes {
		if want := map[bool]int{true: 201, false: 401}[i == 6]; c != want {
			t.Errorf("req %d: %d want %d", i, c, want)
		}
	}
}

func TestRegisterBlankEmail(t *testing.T) {
	a := apptest.New(t)
	a.Signup(t, "a@example.com")
	b := reg("")
	delete(b, "pairing_code")
	b["email"], b["password"] = "   ", apptest.Password
	r, m := a.Do("POST", "/device/v1/register", b)
	if r.StatusCode != 422 || m["error"].(map[string]any)["code"] != "invalid_credentials_input" {
		t.Fatalf("%d %v", r.StatusCode, m)
	}
}

func TestPairingCodeNormalization(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "a@example.com")
	code := newCode(t, a, c)
	typed := strings.NewReplacer("0", "o", "1", "l").Replace(strings.ToLower(code))
	typed = typed[:4] + "-" + typed[4:] + " "
	if r, m := a.Do("POST", "/device/v1/register", reg(typed)); r.StatusCode != 201 {
		t.Fatalf("%q: %d %v", typed, r.StatusCode, m)
	}
}
