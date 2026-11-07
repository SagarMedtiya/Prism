import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface UsageSummary {
  keyId: number;
  month: string;
  totalRequests: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalCostUsd: string;
  cacheHits: number;
  cacheMisses: number;
  cacheHitRate: string;
  fallbackCount: number;
}

export default function UsageView() {
  const [keyIds] = useState([1, 2, 3]);
  const [summaries, setSummaries] = useState<UsageSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all(
      keyIds.map((id) =>
        fetch(`${API_BASE}/v1/usage/summary?keyId=${id}`)
          .then((r) => r.json())
          .catch(() => null)
      )
    ).then((results) => {
      setSummaries(results.filter(Boolean));
      setLoading(false);
    });
  }, []);

  if (loading) return <p className="text-gray-500">Loading...</p>;

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Usage by Key</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {summaries.map((s) => (
          <div key={s.keyId} className="bg-white rounded-lg shadow p-6">
            <h3 className="text-lg font-medium text-gray-900 mb-3">
              Key #{s.keyId}
            </h3>
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-gray-500">Requests</dt>
                <dd className="font-medium">{s.totalRequests}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Prompt Tokens</dt>
                <dd className="font-medium">{s.totalPromptTokens?.toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Completion Tokens</dt>
                <dd className="font-medium">{s.totalCompletionTokens?.toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Total Cost</dt>
                <dd className="font-medium text-green-600">${s.totalCostUsd}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Cache Hit Rate</dt>
                <dd className="font-medium">{s.cacheHitRate}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Fallbacks</dt>
                <dd className="font-medium">{s.fallbackCount}</dd>
              </div>
            </dl>
          </div>
        ))}
      </div>
    </div>
  );
}
