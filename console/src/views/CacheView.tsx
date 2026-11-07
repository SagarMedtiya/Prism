import React, { useEffect, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

interface CacheStats {
  keyId: number;
  cacheHits: number;
  cacheMisses: number;
  cacheHitRate: string;
}

export default function CacheView() {
  const [keyIds] = useState([1, 2, 3]);
  const [stats, setStats] = useState<CacheStats[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all(
      keyIds.map((id) =>
        fetch(`${API_BASE}/v1/usage/summary?keyId=${id}`)
          .then((r) => r.json())
          .then((data) => ({
            keyId: id,
            cacheHits: data.cacheHits || 0,
            cacheMisses: data.cacheMisses || 0,
            cacheHitRate: data.cacheHitRate || '0%',
          }))
          .catch(() => ({
            keyId: id,
            cacheHits: 0,
            cacheMisses: 0,
            cacheHitRate: '0%',
          }))
      )
    ).then((results) => {
      setStats(results);
      setLoading(false);
    });
  }, []);

  if (loading) return <p className="text-gray-500">Loading...</p>;

  return (
    <div>
      <h2 className="text-xl font-semibold mb-4">Cache Hit Rate</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {stats.map((s) => {
          const total = s.cacheHits + s.cacheMisses;
          const hitPercent = total > 0 ? (s.cacheHits / total) * 100 : 0;

          return (
            <div key={s.keyId} className="bg-white rounded-lg shadow p-6">
              <h3 className="text-lg font-medium mb-4">Key #{s.keyId}</h3>

              <div className="w-full bg-gray-200 rounded-full h-4 mb-3">
                <div
                  className="bg-blue-500 h-4 rounded-full transition-all"
                  style={{ width: `${hitPercent}%` }}
                />
              </div>

              <p className="text-2xl font-bold text-center mb-2">
                {s.cacheHitRate}
              </p>

              <div className="flex justify-between text-sm text-gray-500">
                <span>{s.cacheHits} hits</span>
                <span>{s.cacheMisses} misses</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
