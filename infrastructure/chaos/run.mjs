#!/usr/bin/env node
// Runs one AWS FIS chaos experiment (templates/chaos.yaml) against the deployed services and checks its hypothesis:
// the fault costs availability, never money.
//
//   node chaos/run.mjs <experiment>     payment-down | broker-down | events-crash | payment-unreachable | payment-slow
//   node chaos/run.mjs check            only checks the setup and the steady state; starts nothing, changes nothing
//   node chaos/run.mjs all              check, then every experiment in that order, one at a time, and a summary.
//                                       The network ones are skipped while the Event Service has no FIS sidecar.
//
// 1. Steady state, or nothing is broken: the events, payment and rabbitmq services run their desired tasks, the API
//    answers through API Gateway, and the money-at-risk alarm is OK.
// 2. Two test events, A and B (SGD 1.00, Stripe sandbox), and a paid booking for A (pm_card_visa).
// 3. The experiment starts. While the fault is on, A is cancelled (Payment must refund it) and the participant signs
//    up for B (a payment during the fault). events-crash does both once the Event Service is back (no API meanwhile).
// 4. After the experiment, B is cancelled too. Within RECOVERY_MINUTES every payment of A and B must be REFUNDED (or
//    never charged: FAILED), nobody may be charged twice, and the services must be back in steady state by themselves.
//    The experiment itself must complete: stopped by the money-at-risk alarm, or failed, is a failed run.
// Exits 1 when the hypothesis doesn't hold, and lists why.
//
// Environment: CHAOS_ORGANISER_USERNAME / CHAOS_ORGANISER_PASSWORD (a Cognito user in the organiser group) and
// CHAOS_PARTICIPANT_USERNAME / CHAOS_PARTICIPANT_PASSWORD (one in the participant group); STACK_PREFIX (partyfun),
// AWS_REGION (ap-southeast-1) and AWS credentials as for the AWS CLI, which must be installed.
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const PREFIX = process.env.STACK_PREFIX || 'partyfun';
const REGION = process.env.AWS_REGION || process.env.AWS_DEFAULT_REGION || 'ap-southeast-1';
const RECOVERY_MINUTES = Number(process.env.CHAOS_RECOVERY_MINUTES || 10);
const TICKET_PRICE = 1.0; // SGD; Stripe's minimum is 0.50
const PAYMENT_METHOD = 'pm_card_visa'; // Stripe sandbox card that always succeeds

// output: the chaos stack's output holding the template id. network: needs the Event Service's FIS sidecar.
// actDuringFault: false when the Event Service itself is down, so the test traffic waits for it to come back.
const EXPERIMENTS = {
  'payment-down': { output: 'PaymentDownTemplateId', network: false, actDuringFault: true },
  'broker-down': { output: 'BrokerDownTemplateId', network: false, actDuringFault: true },
  'events-crash': { output: 'EventsCrashTemplateId', network: false, actDuringFault: false },
  'payment-unreachable': { output: 'PaymentUnreachableTemplateId', network: true, actDuringFault: true },
  'payment-slow': { output: 'PaymentSlowTemplateId', network: true, actDuringFault: true },
};
const SERVICES = [
  [`${PREFIX}-events`, 'events'],
  [`${PREFIX}-payment`, 'payment'],
  [PREFIX, 'rabbitmq'],
];
const TERMINAL = ['completed', 'stopped', 'failed', 'cancelled'];

const started = Date.now();
let violations = []; // of the experiment running now

function log(message) {
  const seconds = Math.floor((Date.now() - started) / 1000);
  const stamp = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  console.log(`[+${stamp}] ${message}`);
}

function violation(message) {
  violations.push(message);
  log(`HYPOTHESIS VIOLATED: ${message}`);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---- AWS (through the AWS CLI, so it uses the same credentials as the deploy jobs) ----

function aws(...args) {
  const output = execFileSync('aws', [...args, '--region', REGION, '--output', 'json'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return output.trim() ? JSON.parse(output) : null;
}

function stackOutputs(stack) {
  let described;
  try {
    described = aws('cloudformation', 'describe-stacks', '--stack-name', `${PREFIX}-${stack}`);
  } catch {
    throw new Error(`stack ${PREFIX}-${stack} not found: deploy it first`);
  }
  return Object.fromEntries((described.Stacks[0].Outputs || []).map((o) => [o.OutputKey, o.OutputValue]));
}

function serviceProblems() {
  const problems = [];
  for (const [cluster, name] of SERVICES) {
    const service = aws('ecs', 'describe-services', '--cluster', cluster, '--services', name).services?.[0];
    if (!service || service.status !== 'ACTIVE') {
      problems.push(`${name}: no active service in cluster ${cluster}`);
    } else if (service.desiredCount === 0 || service.runningCount < service.desiredCount) {
      problems.push(`${name}: ${service.runningCount} of ${service.desiredCount} task(s) running`);
    }
  }
  return problems;
}

function alarmState(alarmName) {
  return aws('cloudwatch', 'describe-alarms', '--alarm-names', alarmName).MetricAlarms?.[0]?.StateValue ?? 'MISSING';
}

function eventsHasFaultInjection() {
  const service = aws('ecs', 'describe-services', '--cluster', `${PREFIX}-events`, '--services', 'events').services[0];
  const definition = aws('ecs', 'describe-task-definition', '--task-definition', service.taskDefinition).taskDefinition;
  return definition.enableFaultInjection === true
    && definition.containerDefinitions.some((container) => container.name === 'amazon-ssm-agent');
}

function experiment(id) {
  return aws('fis', 'get-experiment', '--id', id).experiment;
}

// ---- party.fun API, signed in through Cognito like the frontend ----

// The frontend's user id for the other services: 32-bit FNV-1a of the Cognito sub (frontend src/api/serviceIds.ts)
function toServiceId(sub) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < sub.length; i++) {
    hash ^= sub.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 1) || 1;
}

async function signIn(username, password, clientId) {
  if (!username || !password) {
    throw new Error('set CHAOS_ORGANISER_USERNAME / _PASSWORD and CHAOS_PARTICIPANT_USERNAME / _PASSWORD');
  }
  const response = await fetch(`https://cognito-idp.${REGION}.amazonaws.com/`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-amz-json-1.1',
      'X-Amz-Target': 'AWSCognitoIdentityProviderService.InitiateAuth',
    },
    body: JSON.stringify({
      AuthFlow: 'USER_PASSWORD_AUTH',
      ClientId: clientId,
      AuthParameters: { USERNAME: username, PASSWORD: password },
    }),
  });
  const body = await response.json();
  const token = body.AuthenticationResult?.AccessToken;
  if (!token) {
    throw new Error(`Cognito sign-in failed for ${username}: ${body.__type ?? response.status}`);
  }
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  return { username, token, userId: toServiceId(claims.sub), groups: claims['cognito:groups'] ?? [] };
}

let apiUrl;

// Never throws: a fault is expected to fail requests. status 0 means no HTTP answer at all.
async function api(user, method, path, body) {
  const begun = Date.now();
  try {
    const response = await fetch(apiUrl + path, {
      method,
      headers: {
        Authorization: `Bearer ${user.token}`,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(60_000),
    });
    const text = await response.text();
    let parsed = text;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      // not JSON (e.g. API Gateway's own errors): keep the text
    }
    return { status: response.status, body: parsed, ms: Date.now() - begun };
  } catch (error) {
    return { status: 0, body: String(error.cause?.code ?? error.message), ms: Date.now() - begun };
  }
}

function describe(result) {
  const detail = typeof result.body === 'object' && result.body
    ? (result.body.status ? `status ${result.body.status}` : result.body.detail ?? result.body.message ?? '')
    : String(result.body ?? '').slice(0, 120);
  return `HTTP ${result.status || 'no answer'} in ${(result.ms / 1000).toFixed(1)} s${detail ? ` (${detail})` : ''}`;
}

// LocalDateTime as the Event Service expects it (UTC, no zone)
function at(days, hours = 0) {
  return new Date(Date.now() + days * 86_400_000 + hours * 3_600_000).toISOString().slice(0, 19);
}

async function steadyStateProblems(participant, alarmName) {
  const problems = serviceProblems();
  const events = await api(participant, 'GET', '/events');
  if (events.status !== 200) problems.push(`GET /events: ${describe(events)}`);
  const payments = await api(participant, 'GET', `/payments/user/${participant.userId}`);
  if (payments.status !== 200) problems.push(`GET /payments/user/{id}: ${describe(payments)}`);
  const alarm = alarmState(alarmName);
  if (alarm !== 'OK' && alarm !== 'INSUFFICIENT_DATA') problems.push(`alarm ${alarmName} is ${alarm}`);
  return problems;
}

async function waitForSteadyState(participant, alarmName, minutes) {
  const deadline = Date.now() + minutes * 60_000;
  let problems;
  while ((problems = await steadyStateProblems(participant, alarmName)).length > 0) {
    if (Date.now() >= deadline) return problems;
    await sleep(10_000);
  }
  return [];
}

// ---- The run ----

// Reads the stacks and signs both accounts in. Done again for every experiment: an access token lasts an hour.
async function setUp() {
  const chaos = stackOutputs('chaos');
  apiUrl = stackOutputs('api').ApiUrl.replace(/\/+$/, '');
  const clientId = stackOutputs('cognito').AppClientId;

  const organiser = await signIn(process.env.CHAOS_ORGANISER_USERNAME, process.env.CHAOS_ORGANISER_PASSWORD, clientId);
  const participant = await signIn(process.env.CHAOS_PARTICIPANT_USERNAME, process.env.CHAOS_PARTICIPANT_PASSWORD,
    clientId);
  if (!organiser.groups.includes('organiser') && !organiser.groups.includes('admin')) {
    throw new Error(`${organiser.username} is not in the organiser group (groups: ${organiser.groups.join(', ') || 'none'})`);
  }
  log(`Signed in: organiser ${organiser.username}, participant ${participant.username} (user id ${participant.userId})`);
  return { chaos, alarmName: chaos.MoneyAtRiskAlarmName, organiser, participant };
}

// No fault is injected into a broken system. waitMinutes gives it that long to get there (between experiments).
async function requireSteadyState(participant, alarmName, waitMinutes = 0) {
  log('Checking the steady state');
  const problems = await waitForSteadyState(participant, alarmName, waitMinutes);
  if (problems.length > 0) {
    const error = new Error(`not in steady state, so no experiment runs:\n  - ${problems.join('\n  - ')}`);
    error.notSteady = true;
    throw error;
  }
  log('Steady state: services running, API answering, money-at-risk alarm OK');
}

async function check() {
  const { chaos, alarmName, participant } = await setUp();
  await requireSteadyState(participant, alarmName);
  const faultInjection = eventsHasFaultInjection();
  for (const [experimentName, spec] of Object.entries(EXPERIMENTS)) {
    const template = aws('fis', 'get-experiment-template', '--id', chaos[spec.output]).experimentTemplate;
    const ready = !spec.network || faultInjection;
    log(`${experimentName}: template ${template.id} ${ready ? 'ready'
      : 'NOT ready: deploy the Event Service with FaultInjection=true (EVENTS_FAULT_INJECTION=true)'}`);
  }
  log('Check done: nothing was started or changed');
}

// Runs one experiment and returns the hypothesis violations it found (none: the hypothesis held). Throws when it
// can't run (no steady state, a setup step failing); the test events are cancelled either way.
async function runExperiment(name, waitMinutes = 0) {
  violations = [];
  const runId = randomUUID().slice(0, 8);
  const { chaos, alarmName, organiser, participant } = await setUp();
  await requireSteadyState(participant, alarmName, waitMinutes);

  const spec = EXPERIMENTS[name];
  if (spec.network && !eventsHasFaultInjection()) {
    throw new Error(`${name} needs the Event Service's FIS sidecar: set the CI/CD variable EVENTS_FAULT_INJECTION=true `
      + 'and run deploy-events (templates/events.yaml, FaultInjection=true)');
  }

  // Test events: A gets a paid booking before the fault, B is signed up for during it
  const events = {};
  const cancelled = new Set();
  const cancel = async (label) => {
    const result = await api(organiser, 'POST', `/events/${events[label]}/cancel`);
    if (result.status === 200 || result.status === 404) cancelled.add(label);
    return result;
  };

  try {
    for (const label of ['A', 'B']) {
      const created = await api(organiser, 'POST', '/events', {
        title: `Chaos test ${name} ${runId} ${label}`,
        description: 'Created by the chaos experiment runner (chaos/run.mjs), which cancels it and refunds every booking.',
        organiserId: organiser.userId,
        location: 'Chaos test',
        startDateTime: at(30),
        endDateTime: at(30, 2),
        capacity: 5,
        maxCapacity: 5,
        minSignUps: 1,
        expiresAt: at(29),
        ticketPrice: TICKET_PRICE,
        currency: 'SGD',
        status: 'ACTIVE',
      });
      if (created.status !== 201) throw new Error(`could not create test event ${label}: ${describe(created)}`);
      events[label] = created.body.id;
    }
    log(`Test events: A = ${events.A}, B = ${events.B} (SGD ${TICKET_PRICE.toFixed(2)})`);

    const paid = await api(participant, 'POST',
      `/events/${events.A}/signup?userId=${participant.userId}&paymentMethodId=${PAYMENT_METHOD}`);
    if (paid.status !== 201 || paid.body?.status !== 'CONFIRMED') {
      throw new Error(`the baseline signup for A wasn't confirmed, so the system isn't healthy: ${describe(paid)}`);
    }
    log(`Baseline: booking ${paid.body.id} for A is CONFIRMED and paid`);

    // ---- Fault ----
    const templateId = chaos[spec.output];
    const experimentId = aws('fis', 'start-experiment', '--experiment-template-id', templateId,
      '--client-token', `${name}-${runId}`, '--tags', `run=${runId}`).experiment.id;
    log(`FIS experiment ${experimentId} started (${name}, template ${templateId})`);

    // The fault is on once its action ran (stop-task) or runs (network faults). Either needs a moment to take hold:
    // the stopped task shuts down gracefully, and the SSM document has to start in the sidecar.
    let current;
    for (;;) {
      current = experiment(experimentId);
      const action = Object.values(current.actions ?? {})[0];
      if (TERMINAL.includes(current.state.status) && current.state.status !== 'completed') break;
      if (action && ['running', 'completed'].includes(action.state?.status)) {
        await sleep(20_000);
        break;
      }
      await sleep(3_000);
    }
    if (TERMINAL.includes(current.state.status) && current.state.status !== 'completed') {
      throw new Error(`experiment ${current.state.status} before the fault was on: ${current.state.reason}`);
    }
    log('Fault is on');

    const testTraffic = async (when) => {
      let result = await cancel('A');
      for (let attempt = 1; result.status !== 200 && result.status !== 404 && attempt < 3; attempt++) {
        await sleep(5_000);
        result = await cancel('A');
      }
      log(`${when}: cancelling A (Payment must refund it): ${describe(result)}`);
      if (!cancelled.has('A')) {
        violation(`A could not be cancelled ${when.toLowerCase()} (${describe(result)}): cancelling an event must `
          + 'not depend on Payment or the broker');
      }
      const signup = await api(participant, 'POST',
        `/events/${events.B}/signup?userId=${participant.userId}&paymentMethodId=${PAYMENT_METHOD}`);
      log(`${when}: signing up for B: ${describe(signup)}`);
    };

    if (spec.actDuringFault) {
      await testTraffic('During the fault');
    } else {
      // The Event Service is down: measure how long the API stays unavailable, then send the traffic
      const down = Date.now();
      let probe;
      while ((probe = await api(participant, 'GET', '/events')).status !== 200) {
        if (Date.now() - down > RECOVERY_MINUTES * 60_000) break;
        await sleep(5_000);
      }
      log(`Event Service API back after ${Math.round((Date.now() - down) / 1000)} s (${describe(probe)})`);
      await testTraffic('After the restart');
    }

    for (;;) {
      current = experiment(experimentId);
      if (TERMINAL.includes(current.state.status)) break;
      await sleep(10_000);
    }
    log(`Experiment ${current.state.status}: ${current.state.reason ?? ''}`);
    if (current.state.status !== 'completed') {
      violation(`the experiment ${current.state.status} (${current.state.reason}); the money-at-risk alarm is `
        + `${alarmState(alarmName)}`);
    }

    // ---- Recovery: by itself, no operator ----
    log(`Waiting up to ${RECOVERY_MINUTES} min for the steady state to return`);
    const after = await waitForSteadyState(participant, alarmName, RECOVERY_MINUTES);
    if (after.length > 0) {
      violation(`no steady state ${RECOVERY_MINUTES} min after the fault: ${after.join('; ')}`);
    } else {
      log('Steady state is back');
    }
  } finally {
    // Cancelling refunds every booking, whatever the fault left behind: always done, also after an error
    for (const label of ['A', 'B']) {
      if (events[label] === undefined || cancelled.has(label)) continue;
      const result = await cancel(label);
      log(`Cancelling ${label}: ${describe(result)}`);
      if (!cancelled.has(label)) {
        violation(`test event ${label} (${events[label]}) could not be cancelled; cancel it to refund its bookings`);
      }
    }
  }

  // ---- Money: every payment of A and B refunded or never charged, nobody charged twice ----
  log(`Waiting up to ${RECOVERY_MINUTES} min for every payment of A and B to be refunded`);
  const ids = new Set([events.A, events.B]);
  const deadline = Date.now() + RECOVERY_MINUTES * 60_000;
  let payments = [];
  for (;;) {
    const result = await api(participant, 'GET', `/payments/user/${participant.userId}`);
    if (result.status === 200 && Array.isArray(result.body)) {
      payments = result.body.filter((payment) => ids.has(payment.eventId));
      const open = payments.filter((payment) => !['REFUNDED', 'FAILED'].includes(payment.status));
      if (open.length === 0 || open.some((payment) => payment.status === 'REFUND_FAILED')) break;
    }
    if (Date.now() > deadline) break;
    await sleep(15_000);
  }
  for (const payment of payments) {
    log(`Payment ${payment.paymentId} (event ${payment.eventId === events.A ? 'A' : 'B'}, Payment's booking `
      + `${payment.bookingId}): ${payment.status}${payment.paidAt ? `, paid ${payment.paidAt}` : ''}`
      + `${payment.refundedAt ? `, refunded ${payment.refundedAt}` : ''}`);
  }
  if (!payments.some((payment) => payment.eventId === events.A && payment.paidAt)) {
    violation(`Payment has no paid payment for A (${events.A}), so the refund could not be checked`);
  }
  for (const payment of payments) {
    if (payment.status === 'REFUND_FAILED') {
      violation(`payment ${payment.paymentId} is REFUND_FAILED: the refund is in Payment's DLQ and needs an admin`);
    } else if (!['REFUNDED', 'FAILED'].includes(payment.status)) {
      violation(`payment ${payment.paymentId} is still ${payment.status} ${RECOVERY_MINUTES} min after its event `
        + 'was cancelled: money not returned');
    }
  }
  for (const label of ['A', 'B']) {
    const charges = payments.filter((payment) => payment.eventId === events[label] && payment.paidAt).length;
    if (charges > 1) violation(`the participant was charged ${charges} times for event ${label}`);
  }

  return violations;
}

function report(name, found) {
  console.log();
  if (found.length > 0) {
    console.log(`${name}: FAILED, the fault cost more than availability:`);
    for (const message of found) console.log(`  - ${message}`);
  } else {
    console.log(`${name}: PASSED. Every payment was refunded or never charged, nobody was charged twice, and the `
      + 'services recovered by themselves.');
  }
}

// check, then every experiment in EXPERIMENTS order. A failed experiment doesn't stop the rest, but a system that
// doesn't return to steady state within RECOVERY_MINUTES does. Returns the exit code.
async function runAll() {
  await check();
  const faultInjection = eventsHasFaultInjection();
  const results = [];
  for (const name of Object.keys(EXPERIMENTS)) {
    console.log();
    log(`===== ${name} =====`);
    if (results.some((result) => result.notSteady)) {
      log('Not run: the system did not return to steady state');
      results.push({ name, verdict: 'NOT RUN', detail: 'the system did not return to steady state' });
      continue;
    }
    if (EXPERIMENTS[name].network && !faultInjection) {
      log('Skipped: the Event Service runs without FaultInjection=true (EVENTS_FAULT_INJECTION)');
      results.push({ name, verdict: 'SKIPPED', detail: 'needs EVENTS_FAULT_INJECTION=true and deploy-events' });
      continue;
    }
    try {
      const found = await runExperiment(name, RECOVERY_MINUTES);
      report(name, found);
      results.push({ name, verdict: found.length > 0 ? 'FAILED' : 'PASSED', detail: found.join('; ') });
    } catch (error) {
      log(`ERROR: ${error.message}`);
      const message = error.message.replace(/:\n\s*-\s*/, ': ').replace(/\n\s*-\s*/g, '; ');
      const detail = [message, ...violations].join('; ');
      results.push({ name, verdict: 'ERROR', detail, notSteady: error.notSteady });
    }
  }
  console.log();
  console.log('Summary:');
  for (const { name, verdict, detail } of results) {
    console.log(`  ${name.padEnd(20)} ${verdict.padEnd(8)} ${detail}`);
  }
  return results.some(({ verdict }) => verdict === 'FAILED' || verdict === 'ERROR') ? 1 : 0;
}

async function main() {
  const name = process.argv[2];
  if (name === 'check') {
    await check();
  } else if (name === 'all') {
    process.exitCode = await runAll();
  } else if (EXPERIMENTS[name]) {
    const found = await runExperiment(name);
    report(name, found);
    if (found.length > 0) process.exitCode = 1;
  } else {
    console.error(`usage: node chaos/run.mjs <${Object.keys(EXPERIMENTS).join(' | ')} | check | all>`);
    process.exit(2);
  }
}

main().catch((error) => {
  console.error(`ERROR: ${error.message}`);
  if (violations.length > 0) console.error(`Violations so far:\n  - ${violations.join('\n  - ')}`);
  process.exit(1);
});
