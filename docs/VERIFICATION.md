# Verification

## Java tests

```bash
./mvnw -B verify
```

The 48 tests cover accounts, JWT validation, public rooms, message storage and pagination, concurrent retries, cursor ordering, connection tickets, Origin checks, actual HTTP/WebSocket exchanges, and connection expiry. Java tests use H2 and local delivery; Redis adapter unit tests use test doubles.

## PostgreSQL and Redis integration

Start the two-instance stack and install the pinned test dependencies:

```bash
docker compose --profile scale up -d --build --wait
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tests/requirements.txt
python tests/integration/check_chat.py
```

The script checks two healthy instances, shared account and room state, cross-instance single-use tickets, rejected origins and ticket replay, live delivery through Redis, exact idempotent responses through PostgreSQL, cursor pagination, concurrent writes, and real ticket expiry. Test accounts and rooms use random names. The script leaves the database and volumes intact.

`RELAY_BASE_URL` and `RELAY_SECOND_URL` select the two origins; defaults are `http://localhost:18080` and `http://localhost:18081`. Both must be allowed by the application configuration. If changing the Compose ports, update these test URLs too.

## Browser acceptance

```bash
python -m playwright install chromium
python tests/browser/check_browser.py
```

On Linux, use `python -m playwright install --with-deps chromium` when the required system packages are missing. `--headed` shows the browser during a local run.

The browser check uses two real application instances. It verifies account and room flows, actual received WebSocket frames, literal-text rendering, stored history and older pages, session restoration, recovery of 105 missed messages, a lost HTTP response after a committed send, stable client IDs on retry, mobile layout, and sign-out. The retry test suppresses only live message echoes so that the UI's uncertain-delivery state can be checked; storage and HTTP responses come from the real servers.

Screenshots are written to the ignored `artifacts/browser` directory. `ARTIFACT_DIR` can select another location.

## Kubernetes manifests

```bash
kubectl kustomize deploy/k8s | kubeconform -strict -summary -kubernetes-version 1.34.0
```

CI uses kubeconform 0.8.0 and verifies the downloaded tool's checksum. The nine rendered resources are checked against Kubernetes schemas. This check validates manifest structure; it does not start a Kubernetes cluster.

## Continuous integration

The GitHub Actions workflow has three jobs:

1. Verify the Java application.
2. Build the Docker image, start PostgreSQL, Redis, and two app containers, then run integration and browser checks.
3. Render and validate the Kubernetes examples.

CI generates isolated test credentials and stops its Compose services after the run.
