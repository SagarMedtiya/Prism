import React, { useState } from 'react';
import UsageView from './views/UsageView';
import RequestsView from './views/RequestsView';
import CacheView from './views/CacheView';

type Tab = 'usage' | 'requests' | 'cache';

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('usage');

  const tabs: { id: Tab; label: string }[] = [
    { id: 'usage', label: 'Usage by Key' },
    { id: 'requests', label: 'Recent Requests' },
    { id: 'cache', label: 'Cache Hit Rate' },
  ];

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-indigo-600 text-white px-6 py-4">
        <h1 className="text-2xl font-bold">Prism Ops Console</h1>
        <p className="text-indigo-200 text-sm">LLM Gateway & Semantic Cache</p>
      </header>

      <nav className="bg-white border-b px-6">
        <div className="flex space-x-4">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`py-3 px-4 text-sm font-medium border-b-2 transition-colors ${
                activeTab === tab.id
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </nav>

      <main className="p-6 max-w-7xl mx-auto">
        {activeTab === 'usage' && <UsageView />}
        {activeTab === 'requests' && <RequestsView />}
        {activeTab === 'cache' && <CacheView />}
      </main>
    </div>
  );
}
