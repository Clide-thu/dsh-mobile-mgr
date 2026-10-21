#!/usr/bin/env node
/**
 * Portable Android build toolchain bootstrap.
 *
 * The build host may have no JDK, no Android SDK and no Gradle. This script
 * downloads everything into `.toolchain/` (self-contained, gitignored):
 *
 *   .toolchain/jdk/          Temurin JDK 17 (javac, keytool, jar)
 *   .toolchain/android/      platform-<N>/android.jar + build-tools/<ver>/{aapt2,d8,apksigner,zipalign}
 *   .toolchain/libs/         com.github.mwiede:jsch (single-jar SSH client)
 *   .toolchain/toolchain.json  absolute paths consumed by tools/build.mjs
 *
 * Network access is done through Node's fetch (the only HTTP stack that works
 * under this host's sandbox; schannel-based clients fail with SEC_E_NO_CREDENTIALS).
 *
 * Usage: node tools/setup-toolchain.mjs [--force]
 */
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, rm, stat, writeFile, readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const ROOT = path.resolve(import.meta.dirname, '..');
const TC = path.join(ROOT, '.toolchain');
const CACHE = path.join(TC, 'downloads');
const FORCE = process.argv.includes('--force');

const JDK_URL =
  'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse';
const JSCH_VERSION = '2.28.8';
const JSCH_URL = `https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/${JSCH_VERSION}/jsch-${JSCH_VERSION}.jar`;
// HostsCodec uses org.json, which Android ships in the framework. Desktop JVM
// unit tests need the same API from Maven.
const ORGJSON_VERSION = '20240303';
const ORGJSON_URL = `https://repo.maven.apache.org/maven2/org/json/json/${ORGJSON_VERSION}/json-${ORGJSON_VERSION}.jar`;
const REPO2_URL = 'https://dl.google.com/android/repository/repository2-1.xml';
const ANDROID_PLATFORM = 'platforms;android-35';
const BUILD_TOOLS = 'build-tools;35.0.0';

function log(...parts) {
  console.log('[toolchain]', ...parts);
}

async function exists(file) {
  try {
    await stat(file);
    return true;
  } catch {
    return false;
  }
}

async function download(url, dest, label) {
  if (!FORCE && (await exists(dest))) {
    const size = (await stat(dest)).size;
    if (size > 0) {
      log(`${label}: cached (${(size / 1e6).toFixed(1)} MB)`);
      return size;
    }
  }
  await mkdir(path.dirname(dest), { recursive: true });
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(url, { redirect: 'follow' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const total = Number(response.headers.get('content-length') ?? 0);
      const out = createWriteStream(dest);
      let received = 0;
      let lastLog = 0;
      for await (const chunk of response.body) {
        received += chunk.length;
        if (!out.write(chunk)) await new Promise((resolve) => out.once('drain', resolve));
        if (received - lastLog > 25 * 1e6) {
          lastLog = received;
          log(`${label}: ${(received / 1e6).toFixed(0)}${total ? `/${(total / 1e6).toFixed(0)}` : ''} MB`);
        }
      }
      await new Promise((resolve, reject) => out.end((err) => (err ? reject(err) : resolve())));
      log(`${label}: downloaded ${(received / 1e6).toFixed(1)} MB`);
      return received;
    } catch (error) {
      log(`${label}: attempt ${attempt} failed: ${error.message}`);
      if (attempt === 3) throw error;
      await new Promise((resolve) => setTimeout(resolve, 1500 * attempt));
    }
  }
  throw new Error(`could not download ${url}`);
}

/** Extract a .zip using Windows bsdtar (present since Windows 10 1803). */
function extractZip(zip, dest) {
  return new Promise((resolve, reject) => {
    const result = spawnSync('tar', ['-xf', zip, '-C', dest], { stdio: 'inherit' });
    if (result.status === 0) resolve();
    else reject(new Error(`tar failed with exit ${result.status} for ${zip}`));
  });
}

async function findFile(root, name, depth = 4) {
  if (depth < 0) return undefined;
  const entries = await readdir(root, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(root, entry.name);
    if (entry.isFile() && entry.name.toLowerCase() === name.toLowerCase()) return full;
    if (entry.isDirectory()) {
      const nested = await findFile(full, name, depth - 1);
      if (nested) return nested;
    }
  }
  return undefined;
}

/**
 * Run a child process for its exit status.
 *
 * stdio MUST be 'inherit': under the DSH Windows sandbox a child process cannot
 * use named pipes, so Node's default `stdio: 'pipe'` fails with EPERM. Inherit
 * and file descriptors are allowed.
 */
function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} -> exit ${result.status}`);
  }
  return result.status;
}

/**
 * Resolve the current download URLs from Google's SDK manifest.
 *
 * Platform archives carry no `<host-os>` element (they are host independent),
 * while build-tools ship one archive per host OS. Picking by URL shape rather
 * than by package block order keeps this stable across manifest revisions.
 */
async function parseSdkUrls() {
  log('fetching Android SDK repository manifest');
  const response = await fetch(REPO2_URL);
  if (!response.ok) throw new Error(`repository2-1.xml: HTTP ${response.status}`);
  const xml = await response.text();
  const urls = [...xml.matchAll(/<url>([^<]+)<\/url>/g)].map((match) => match[1]);

  const platform = urls
    .filter((url) => /^platform-35_r\d+\.zip$/.test(url))
    .sort((a, b) => Number(b.match(/_r(\d+)/)[1]) - Number(a.match(/_r(\d+)/)[1]))[0];
  const buildTools = urls
    .filter((url) => /^build-tools_r35(\.\d+)?_windows\.zip$/.test(url))
    .sort((a, b) => {
      const va = Number(a.match(/r35(?:\.(\d+))?/)[1] ?? 0);
      const vb = Number(b.match(/r35(?:\.(\d+))?/)[1] ?? 0);
      return vb - va;
    })[0];
  if (!platform) throw new Error('platform-35 archive not found in SDK manifest');
  if (!buildTools) throw new Error('build-tools r35 windows archive not found in SDK manifest');
  log('platform archive:', platform);
  log('build-tools archive:', buildTools);
  const base = 'https://dl.google.com/android/repository/';
  return new Map([
    [ANDROID_PLATFORM, { url: base + platform, checksum: undefined }],
    [BUILD_TOOLS, { url: base + buildTools, checksum: undefined }]
  ]);
}

async function main() {
  await mkdir(CACHE, { recursive: true });
  await mkdir(path.join(TC, 'libs'), { recursive: true });

  // --- JDK -----------------------------------------------------------------
  if (!(await exists(path.join(TC, 'jdk', 'bin', 'javac.exe')))) {
    const zip = path.join(CACHE, 'temurin17.zip');
    await download(JDK_URL, zip, 'JDK 17');
    log('extracting JDK');
    const staging = path.join(TC, 'jdk-staging');
    await rm(staging, { recursive: true, force: true });
    await mkdir(staging, { recursive: true });
    await extractZip(zip, staging);
    const javac = await findFile(staging, 'javac.exe', 3);
    if (!javac) throw new Error('javac.exe not found in JDK archive');
    const jdkHome = path.dirname(path.dirname(javac));
    await rm(path.join(TC, 'jdk'), { recursive: true, force: true });
    await mkdir(path.dirname(path.join(TC, 'jdk')), { recursive: true });
    const { rename } = await import('node:fs/promises');
    await rename(jdkHome, path.join(TC, 'jdk'));
    await rm(staging, { recursive: true, force: true });
  } else {
    log('JDK: cached');
  }
  const java = path.join(TC, 'jdk', 'bin', 'java.exe');
  const javacBin = path.join(TC, 'jdk', 'bin', 'javac.exe');
  log('checking java');
  run(java, ['-version']);

  // --- Android SDK ---------------------------------------------------------
  const sdkUrls = await parseSdkUrls();
  const androidRoot = path.join(TC, 'android');
  const platformDir = path.join(androidRoot, 'platform-35');
  const buildToolsDir = path.join(androidRoot, 'build-tools', '35.0.0');
  if (!(await exists(path.join(platformDir, 'android.jar')))) {
    const { url, checksum } = sdkUrls.get(ANDROID_PLATFORM);
    const zip = path.join(CACHE, 'platform-35.zip');
    await download(url, zip, 'Android platform 35');
    log(`platform zip checksum (expected ${checksum ?? 'n/a'})`);
    await mkdir(platformDir, { recursive: true });
    await extractZip(zip, platformDir);
    // Some zips extract to android-35/ inside; flatten android.jar to the top.
    let jar = await findFile(platformDir, 'android.jar', 2);
    if (!jar) throw new Error('android.jar not found in platform archive');
    if (path.dirname(jar) !== platformDir) {
      const { copyFile } = await import('node:fs/promises');
      await copyFile(jar, path.join(platformDir, 'android.jar'));
    }
  } else {
    log('Android platform 35: cached');
  }
  if (!(await exists(path.join(buildToolsDir, 'aapt2.exe')))) {
    const { url, checksum } = sdkUrls.get(BUILD_TOOLS);
    const zip = path.join(CACHE, 'build-tools-35.zip');
    await download(url, zip, 'Android build-tools 35');
    log(`build-tools zip checksum (expected ${checksum ?? 'n/a'})`);
    await mkdir(buildToolsDir, { recursive: true });
    await extractZip(zip, buildToolsDir);
  } else {
    log('Android build-tools 35: cached');
  }
  const aapt2 = await findFile(buildToolsDir, 'aapt2.exe', 2);
  const zipalign = await findFile(buildToolsDir, 'zipalign.exe', 2);
  const apksignerJar = await findFile(buildToolsDir, 'apksigner.jar', 3);
  if (!aapt2 || !zipalign || !apksignerJar) {
    throw new Error(`build-tools incomplete: aapt2=${aapt2} zipalign=${zipalign} apksigner=${apksignerJar}`);
  }
  log('checking aapt2');
  run(aapt2, ['version']);

  // --- SSH client library --------------------------------------------------
  const jschJar = path.join(TC, 'libs', `jsch-${JSCH_VERSION}.jar`);
  if (!(await exists(jschJar))) {
    await download(JSCH_URL, jschJar, 'JSch');
  }
  log('jsch:', (await stat(jschJar)).size, 'bytes');

  const jsonJar = path.join(TC, 'libs', `json-${ORGJSON_VERSION}.jar`);
  if (!(await exists(jsonJar))) {
    await download(ORGJSON_URL, jsonJar, 'org.json');
  }
  log('org.json:', (await stat(jsonJar)).size, 'bytes');

  // --- manifest ------------------------------------------------------------
  const buildToolsRoot = path.dirname(apksignerJar);
  const manifest = {
    javaHome: path.join(TC, 'jdk'),
    java,
    javac: javacBin,
    jar: path.join(TC, 'jdk', 'bin', 'jar.exe'),
    keytool: path.join(TC, 'jdk', 'bin', 'keytool.exe'),
    androidJar: path.join(platformDir, 'android.jar'),
    aapt2,
    zipalign,
    apksignerJar,
    buildToolsRoot,
    d8Jar: (await findFile(buildToolsRoot, 'd8.jar', 2)) ?? null,
    jschJar,
    jsonJar,
    sdkPackages: { platform: ANDROID_PLATFORM, buildTools: BUILD_TOOLS },
    toolchainVersion: 1
  };
  if (!manifest.d8Jar) throw new Error('d8.jar not found in build-tools');
  await writeFile(path.join(TC, 'toolchain.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  log('wrote .toolchain/toolchain.json');
  log('toolchain ready');
}

main().catch((error) => {
  console.error('[toolchain] FAILED:', error);
  process.exit(1);
});
