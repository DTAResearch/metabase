import fs from "node:fs";
import path from "node:path";

import installLogsPrinter from "cypress-terminal-report/src/installLogsPrinter";

import * as ciTasks from "./ci_tasks";
import { collectFailingTests } from "./collectFailedTests";
import {
  removeDirectory,
  verifyDownloadTasks,
} from "./commands/downloads/downloadUtils";
import webpackConfig from "./component-webpack.config";
import * as dbTasks from "./db_tasks";
import { signJwt } from "./helpers/e2e-jwt-tasks";

const createBundler = require("@bahmutov/cypress-esbuild-preprocessor"); // This function is called when a project is opened or re-opened (e.g. due to the project's config changing)
const {
  NodeModulesPolyfillPlugin,
} = require("@esbuild-plugins/node-modules-polyfill");
const cypressSplit = require("cypress-split");

const isEnterprise = process.env["MB_EDITION"] === "ee";
const isCI = process.env["CYPRESS_CI"] === "true";

const hasSnowplowMicro = process.env["MB_SNOWPLOW_AVAILABLE"];
const snowplowMicroUrl = process.env["MB_SNOWPLOW_URL"];

const isQaDatabase = process.env["QA_DB_ENABLED"] === "true";

const sourceVersion = process.env["CROSS_VERSION_SOURCE"];
const targetVersion = process.env["CROSS_VERSION_TARGET"];

const isEmbeddingSdk = process.env.CYPRESS_IS_EMBEDDING_SDK === "true";

// docs say that tsconfig paths should handle aliases, but they don't
const assetsResolverPlugin = {
  name: "assetsResolver",
  setup(build) {
    // Redirect all paths starting with "assets/" to "resources/"
    build.onResolve({ filter: /^assets\// }, (args) => {
      return {
        path: path.join(
          __dirname,
          "../../resources/frontend_client/app",
          args.path,
        ),
      };
    });
  },
};

// these are special and shouldn't be chunked out arbitrarily
const specBlacklist = ["/embedding-sdk/", "/cross-version/"];

function getSplittableSpecs(specs) {
  return specs.filter((spec) => {
    return !specBlacklist.some((blacklistedPath) =>
      spec.includes(blacklistedPath),
    );
  });
}

const defaultConfig = {
  // This is the functionality of the old cypress-plugins.js file
  setupNodeEvents(on, config) {
    // `on` is used to hook into various events Cypress emits
    // `config` is the resolved Cypress config

    // CLI grep can't handle commas in the name
    // needed when we want to run only specific tests
    config.env.grep ??= process.env.GREP;

    // cypress-terminal-report
    if (isCI) {
      installLogsPrinter(on, {
        printLogsToConsole: "never",
      });
    }

    /********************************************************************
     **                        PREPROCESSOR                            **
     ********************************************************************/
    on(
      "file:preprocessor",
      createBundler({
        loader: {
          ".svg": "text",
        },
        plugins: [NodeModulesPolyfillPlugin(), assetsResolverPlugin],
        sourcemap: "inline",
      }),
    );

    /********************************************************************
     **                         BROWSERS                               **
     ********************************************************************/

    on("before:browser:launch", (browser = {}, launchOptions) => {
      //  Open dev tools in Chrome by default
      if (browser.name === "chrome" || browser.name === "chromium") {
        launchOptions.args.push("--auto-open-devtools-for-tabs");
      }

      // Start browsers with prefers-reduced-motion set to "reduce"
      if (browser.family === "firefox") {
        launchOptions.preferences["ui.prefersReducedMotion"] = 1;
      }

      if (browser.family === "chromium") {
        launchOptions.args.push("--force-prefers-reduced-motion");
      }

      return launchOptions;
    });

    /********************************************************************
     **                           TASKS                                **
     ********************************************************************/
    on("task", {
      log(...messages) {
        console.log(...messages);
        return null; // tasks must have a return value
      },
      ...dbTasks,
      ...ciTasks,
      ...verifyDownloadTasks,
      removeDirectory,
      signJwt,
    });

    /********************************************************************
     **                          CONFIG                                **
     ********************************************************************/

    if (!isQaDatabase) {
      config.excludeSpecPattern = "e2e/snapshot-creators/qa-db.cy.snap.js";
    }

    // `grepIntegrationFolder` needs to point to the root!
    // See: https://github.com/cypress-io/cypress/issues/24452#issuecomment-1295377775
    config.env.grepIntegrationFolder = "../../";
    config.env.grepFilterSpecs = true;

    config.env.IS_ENTERPRISE = isEnterprise;
    config.env.HAS_SNOWPLOW_MICRO = hasSnowplowMicro;
    config.env.SNOWPLOW_MICRO_URL = snowplowMicroUrl;
    config.env.SOURCE_VERSION = sourceVersion;
    config.env.TARGET_VERSION = targetVersion;

    require("@cypress/grep/src/plugin")(config);

    if (isCI) {
      cypressSplit(on, config, getSplittableSpecs);
      collectFailingTests(on, config);
    }

    // this is an official workaround to keep recordings of the failed specs only
    // https://docs.cypress.io/guides/guides/screenshots-and-videos#Delete-videos-for-specs-without-failing-or-retried-tests
    on("after:spec", (spec, results) => {
      if (results && results.video) {
        // Do we have test failures?
        if (results && results.video && results.stats.failures === 0) {
          // delete the video if the spec passed
          fs.unlinkSync(results.video);
        }
      }
    });

    return config;
  },
  supportFile: "e2e/support/cypress.js",
  chromeWebSecurity: false,
  modifyObstructiveCode: false,
  // New `specPattern` is the combination of the old:
  //   1. testFiles and
  //   2. integrationFolder
  specPattern: "e2e/test/**/*.cy.spec.{js,ts}",
  viewportHeight: 800,
  viewportWidth: 1280,
  // enable video recording in run mode
  video: true,
  videoCompression: true,
};

const mainConfig = {
  ...defaultConfig,
  ...(isEmbeddingSdk
    ? {
        chromeWebSecurity: true,
        hosts: {
          "my-site.local": "127.0.0.1",
        },
      }
    : {}),
  projectId: "ywjy9z",
  numTestsKeptInMemory: process.env["CI"] ? 1 : 50,
  reporter: "cypress-multi-reporters",
  reporterOptions: {
    configFile: false,
    // 🤯 mochawesome != cypress-mochawesome-reporter != mochaAwesome (this form does not exist) 🤯
    // See https://glebbahmutov.com/blog/the-awesome-battle/ to compare the first two.
    reporterEnabled: "mochawesome, mocha-junit-reporter",
    mochawesomeReporterOptions: {
      // https://github.com/adamgruber/mochawesome (NOT https://www.npmjs.com/package/cypress-mochawesome-reporter)
      reportDir: "cypress/reports/mochareports",
      reportFilename: "[status]-[name]",
      quiet: true,
      html: true,
      json: true,
    },
    // 🤯🤮mochaJunitReporterReporterOptions 🤮🤯
    // Exercise care when using this poorly documented key:
    // - https://stackoverflow.com/questions/51180963/cypress-tests-with-mocha-multi-reports-not-able-to-get-aggregated-results-for-a
    // - https://stackoverflow.com/questions/37965049/mocha-multi-reporter-with-junit
    // For the curious, ChatGPT 3.5 does NOT get it right
    mochaJunitReporterReporterOptions: {
      mochaFile: "./target/junit/[hash].xml",
      toConsole: false,
    },
  },
  retries: {
    runMode: 1,
    openMode: 0,
  },
};

const snapshotsConfig = {
  ...defaultConfig,
  specPattern: "e2e/snapshot-creators/**/*.cy.snap.js",
  video: false,
};

const crossVersionSourceConfig = {
  ...defaultConfig,
  baseUrl: "http://localhost:3000",
  specPattern: "e2e/test/scenarios/cross-version/source/**/*.cy.spec.{js,ts}",
};

const crossVersionTargetConfig = {
  ...defaultConfig,
  baseUrl: "http://localhost:3001",
  specPattern: "e2e/test/scenarios/cross-version/target/**/*.cy.spec.{js,ts}",
};

const stressTestConfig = {
  ...defaultConfig,
  retries: 0,
};

const embeddingSdkComponentTestConfig = {
  ...defaultConfig,
  video: false,
  specPattern: "e2e/test-component/scenarios/embedding-sdk/**/*.cy.spec.tsx",
  indexHtmlFile: "e2e/support/component-index.html",
  supportFile: "e2e/support/cypress.js",

  reporter: mainConfig.reporter,
  reporterOptions: mainConfig.reporterOptions,
  retries: mainConfig.retries,

  devServer: {
    framework: "react",
    bundler: "webpack",
    webpackConfig: webpackConfig,
  },
};

module.exports = {
  mainConfig,
  snapshotsConfig,
  stressTestConfig,
  crossVersionSourceConfig,
  crossVersionTargetConfig,
  embeddingSdkComponentTestConfig,
};
