import { lazy, Suspense } from "react";
import ReactDOM from "react-dom";
import * as Sentry from "@sentry/react";
import { Integrations } from "@sentry/tracing";

// We do not follow default config approach since we want to init sentry asap
Sentry.init({
  dsn: process.env.REACT_APP_SENTRY_DSN || window.REACT_APP_SENTRY_DSN,
  release: process.env.REACT_APP_WEBAPP_TAG || window.REACT_APP_WEBAPP_TAG || "dev",
  integrations: [new Integrations.BrowserTracing()],
  tracesSampleRate: 1.0, // may need to adjust this in the future
});

const CloudApp = lazy(() => import(`packages/cloud/App`));
const App = lazy(() => import(`./App`));

ReactDOM.render(
  <Suspense fallback={null}>
    {process.env.REACT_APP_CLOUD || window.CLOUD === "true" ? <CloudApp /> : <App />}
  </Suspense>,
  document.getElementById("root")
);
