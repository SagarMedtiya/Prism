import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface RequestEntry {
  id: number;
  keyId: number;
  requestModel: string;
  servingModel: string;
  provider: string;
  status: string;
  errorCode: string | null;
  promptTokens: number;
  completionTokens: number;
  costMicroUsd: number;
  cacheResult: string;
  fallback: boolean;
  latencyMs: number;
  createdAt: string;
}

export default function RequestsView() {
  const [requests, setRequests] = useState<RequestEntry[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`${API_BASE}/v1/usage/requests?page=${page}&size=20`)
      .then((r) => r.json())
      .then((data) => {
        setRequests(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [page]);

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Recent Requests</h2>
      {loading ? (
        <p className="text-gray-500">Loading...</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="min-w-full bg-white rounded-lg shadow text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left">Time</th>
                  <th className="px-4 py-3 text-left">Key</th>
                  <th className="px-4 py-3 text-left">Model</th>
                  <th className="px-4 py-3 text-left">Provider</th>
                  <th className="px-4 py-3 text-left">Status</th>
                  <th className="px-4 py-3 text-left">Cache</th>
                  <th className="px-4 py-3 text-right">Tokens</th>
                  <th className="px-4 py-3 text-right">Cost</th>
                  <th className="px-4 py-3 text-right">Latency</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {requests.map((req) => (
                  <tr key={req.id} className="hover:bg-gray-50">
                    <td className="px-4 py-2 text-gray-500">
                      {new Date(req.createdAt).toLocaleTimeString()}
                    </td>
                    <td className="px-4 py-2">#{req.keyId}</td>
                    <td className="px-4 py-2">{req.servingModel || req.requestModel}</td>
                    <td className="px-4 py-2">{req.provider || '—'}</td>
                    <td className="px-4 py-2">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                        req.status === 'success'
                          ? 'bg-green-100 text-green-700'
                          : 'bg-red-100 text-red-700'
                      }`}>
                        {req.errorCode || req.status}
                      </span>
                    </td>
                    <td className="px-4 py-2">
                      {req.cacheResult === 'hit' && (
                        <span className="text-blue-600 font-medium">HIT</span>
                      )}
                      {req.cacheResult === 'miss' && (
                        <span className="text-gray-400">miss</span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right">
                      {req.promptTokens + req.completionTokens}
                    </td>
                    <td className="px-4 py-2 text-right">
                      ${(req.costMicroUsd / 1_000_000).toFixed(4)}
                    </td>
                    <td className="px-4 py-2 text-right">{req.latencyMs}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex justify-between mt-4">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="px-4 py-2 bg-gray-200 rounded disabled:opacity-50"
            >
              Previous
            </button>
            <span className="py-2 text-gray-500">Page {page + 1}</span>
            <button
              onClick={() => setPage(page + 1)}
              className="px-4 py-2 bg-gray-200 rounded"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
