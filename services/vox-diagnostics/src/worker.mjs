import { handleRequest } from './diagnostics.mjs';

export default {
  async fetch(request, env, ctx) {
    return handleRequest(request, env);
  },
};
