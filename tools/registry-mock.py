"""Local stand-in for the Supabase certificate registry, for testing `aether-ca` without the
service-role key.

Answers the four RPCs from supabase/migrations/20261108130000_aether_registry_functions.sql
the way PostgREST does (same paths, parameter names, JSON shapes and error codes), keeps state
in memory, and enforces the auth rule the real project has: an `apikey` header is required, and
`Authorization: Bearer` is accepted only for JWT-style keys.

    python tools/registry-mock.py 54329
    AETHER_SUPABASE_URL=http://127.0.0.1:54329 AETHER_SUPABASE_SERVICE_KEY=sb_secret_mock bash tools/registry-smoke.sh
"""
import hashlib
import json
import sys
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

cas, devices, certs = {}, {}, {}
SIGS = {
    "aether_register_ca": {"p_public_key_hex", "p_label"},
    "aether_record_certificate": {"p_serial", "p_ca_key_id", "p_device_key_hex", "p_label", "p_profile",
                                  "p_not_before_ms", "p_not_after_ms", "p_certificate_hex"},
    "aether_revoke_certificate": {"p_serial", "p_reason"},
    "aether_certificate_status": {"p_serial"},
}
OPTIONAL = {"aether_revoke_certificate": {"p_reason"}}


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat()


class H(BaseHTTPRequestHandler):
    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

    def do_POST(self):
        key = self.headers.get("apikey", "")
        auth = self.headers.get("Authorization")
        if not key:
            return self.reply(401, {"message": "No API key found in request"})
        if auth and not key.startswith("eyJ"):
            return self.reply(401, {"message": "Authorization must not carry a non-JWT key"})
        if self.headers.get("Content-Type") != "application/json":
            return self.reply(415, {"message": "expected application/json"})
        prefix = "/rest/v1/rpc/"
        if not self.path.startswith(prefix):
            return self.reply(404, {})
        fn = self.path[len(prefix):]
        args = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        need = SIGS.get(fn)
        if need is None or not (need - OPTIONAL.get(fn, set()) <= set(args) <= need):
            return self.reply(404, {"code": "PGRST202", "message": f"Could not find the function public.{fn}"})
        try:
            if fn == "aether_register_ca":
                k = bytes.fromhex(args["p_public_key_hex"])
                kid = hashlib.sha256(k).digest()[:8].hex()
                cas[kid] = {"public_key": k, "label": args["p_label"]}
                return self.reply(200, kid)
            if fn == "aether_record_certificate":
                if args["p_ca_key_id"] not in cas:
                    return self.reply(409, {"code": "23503", "message": "violates foreign key constraint"})
                if args["p_serial"] in certs:
                    return self.reply(409, {"code": "23505", "message": "duplicate key value"})
                dk = args["p_device_key_hex"]
                dev = devices.setdefault(dk, {"id": str(uuid.uuid4())})
                dev["label"] = args["p_label"]
                certs[args["p_serial"]] = dict(args, revoked_at=None, revoke_reason=None)
                return self.reply(200, dev["id"])
            if fn == "aether_revoke_certificate":
                c = certs.get(args["p_serial"])
                if not c or c["revoked_at"]:
                    return self.reply(200, False)
                c["revoked_at"] = datetime.now(timezone.utc).isoformat()
                c["revoke_reason"] = args.get("p_reason")
                return self.reply(200, True)
            if fn == "aether_certificate_status":
                c = certs.get(args["p_serial"])
                if not c:
                    row = {"status": "unknown", "label": None, "profile": None, "not_after": None,
                           "revoked_at": None, "revoke_reason": None}
                else:
                    now_ms = datetime.now(timezone.utc).timestamp() * 1000
                    status = "revoked" if c["revoked_at"] else "expired" if c["p_not_after_ms"] < now_ms else "valid"
                    row = {"status": status, "label": c["p_label"], "profile": c["p_profile"],
                           "not_after": iso(c["p_not_after_ms"]), "revoked_at": c["revoked_at"],
                           "revoke_reason": c["revoke_reason"]}
                return self.reply(200, [row])
        except (ValueError, KeyError) as e:
            return self.reply(400, {"code": "22023", "message": str(e)})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 54329
    print(f"registry mock on http://127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()
