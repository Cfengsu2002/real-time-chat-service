import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const virtualUsers = Number(__ENV.VUS || 10);

export const options = {
  vus: virtualUsers,
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    checks: ['rate>0.99'],
  },
};

function requestOptions(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === 'x' ? value : ((value & 3) | 8)).toString(16);
  });
}

export function setup() {
  const runId = Date.now().toString(36);
  const tokens = [];
  for (let index = 0; index < virtualUsers; index += 1) {
    const response = http.post(`${baseUrl}/api/auth/register`, JSON.stringify({
      username: `load${runId}${index}`,
      password: `Load-test-${runId}-password`,
    }), { headers: { 'Content-Type': 'application/json' } });
    if (response.status !== 200 && response.status !== 201) {
      throw new Error(`测试账号创建失败，HTTP ${response.status}`);
    }
    tokens.push(response.json('data.token'));
  }
  const roomsResponse = http.get(`${baseUrl}/api/rooms`, requestOptions(tokens[0]));
  if (roomsResponse.status !== 200) {
    throw new Error(`读取房间失败，HTTP ${roomsResponse.status}`);
  }
  const rooms = roomsResponse.json('data');
  if (!Array.isArray(rooms) || rooms.length === 0) {
    throw new Error('需要至少一个房间才能运行负载测试');
  }
  return { tokens, roomId: rooms[0].id };
}

export default function (data) {
  const token = data.tokens[(exec.vu.idInTest - 1) % data.tokens.length];
  const url = `${baseUrl}/api/rooms/${data.roomId}/messages`;
  const sent = http.post(url, JSON.stringify({
    content: `k6 HTTP message ${exec.vu.idInTest}/${exec.vu.iterationInScenario}`,
    clientMessageId: uuid(),
  }), requestOptions(token));
  check(sent, { '消息发送成功': (response) => response.status === 200 || response.status === 201 });
  const history = http.get(`${url}?limit=50`, requestOptions(token));
  check(history, { '历史读取成功': (response) => response.status === 200 });
  sleep(1);
}
