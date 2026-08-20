const CACHE_NAME = 'carenderia-pos-v2026-08-21-1';

self.addEventListener('install', event => {
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys
          .filter(key => key !== CACHE_NAME)
          .map(key => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', event => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

const SESSION_FIX_SCRIPT = `<script>
(function(){
  const originalDeleteEmployee = window.deleteEmployee;
  if(typeof originalDeleteEmployee !== 'function') return;

  window.deleteEmployee = async function(id,name){
    if(currentProfile?.role!=='admin'){alert('Admin access required.');return;}
    if(!confirm('Delete employee account "'+name+'"?\\n\\nThis will permanently remove the employee login account. Sales records will remain.'))return;
    if(!navigator.onLine){alert('❌ Internet connection is required to delete an employee.');return;}

    try{
      // Force a fresh access token before calling the Edge Function.
      // Supabase access tokens are short-lived, while refreshSession()
      // obtains a new token when the stored refresh token is still valid.
      let refreshed=await supabaseClient.auth.refreshSession();
      let session=refreshed?.data?.session||null;

      if(refreshed?.error || !session?.access_token){
        const fallback=await supabaseClient.auth.getSession();
        session=fallback?.data?.session||null;
      }

      if(!session?.access_token){
        alert('❌ Your login session has expired. Please log out and log in again.');
        return;
      }

      async function sendDelete(accessToken){
        return fetch(`${SUPABASE_URL}/functions/v1/manage-employee`,{
          method:'POST',
          headers:{
            'Authorization':`Bearer ${accessToken}`,
            'apikey':SUPABASE_KEY,
            'Content-Type':'application/json'
          },
          body:JSON.stringify({action:'delete',id})
        });
      }

      let response=await sendDelete(session.access_token);

      // If the token was rejected anyway, refresh once more and retry.
      if(response.status===401){
        const retryRefresh=await supabaseClient.auth.refreshSession();
        const retrySession=retryRefresh?.data?.session;
        if(retrySession?.access_token){
          response=await sendDelete(retrySession.access_token);
        }
      }

      let result={};
      try{result=await response.json();}catch(e){}

      if(!response.ok){
        alert('❌ '+(result?.error||`Could not delete employee (HTTP ${response.status}).`));
        return;
      }

      alert('✅ Employee account deleted.');
      await loadEmployees();
    }catch(error){
      alert('❌ '+(error?.message||'Could not connect to the employee management service.'));
    }
  };
})();
</script>`;

self.addEventListener('fetch', event => {
  const request = event.request;

  if (request.method !== 'GET') return;

  // Always get the newest index.html when opening the POS.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request, { cache: 'no-store' })
        .then(response => response.text())
        .then(html => {
          // Patch the existing deleteEmployee() at runtime so it refreshes
          // the Supabase session before calling the protected Edge Function.
          const patchedHtml = html.includes('</body>')
            ? html.replace('</body>', SESSION_FIX_SCRIPT + '</body>')
            : html + SESSION_FIX_SCRIPT;
          const patchedResponse = new Response(patchedHtml, {
            status: 200,
            headers: {
              'Content-Type': 'text/html; charset=utf-8',
              'Cache-Control': 'no-store'
            }
          });

          caches.open(CACHE_NAME).then(cache => {
            cache.put(request, patchedResponse.clone());
          });

          return patchedResponse;
        })
        .catch(() =>
          caches.match(request).then(cached =>
            cached || caches.match('./index.html')
          )
        )
    );
    return;
  }

  // Network first for other files, with cache as offline backup.
  event.respondWith(
    fetch(request)
      .then(response => {
        if (response && response.ok) {
          const copy = response.clone();

          caches.open(CACHE_NAME).then(cache => {
            cache.put(request, copy);
          });
        }

        return response;
      })
      .catch(() => caches.match(request))
  );
});
