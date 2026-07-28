package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The bundled multi-node consistency panel (WCON.2): the console's window onto the server's
 * {@code GET /api/consistency} read - the per-node fingerprints every node publishes to the shared store (WO.4
 * per-node numbers) and any divergence the check found between them. Consistent with the enterprise console's
 * consistency page and its {@code /api/admin/consistency} admin API: a per-node table (id, live/stale, heartbeat age,
 * index cursor, config generation, quota) and a divergence list (each a stuck-cursor / config / pointer split with a
 * value-free reason), calling the key-auth'd JSON API with the {@code Jenesis-Repository-Key} header exactly as the
 * {@link LogPanel} does (the free console authenticates the human by session, but the server's {@code /api/consistency}
 * read is key-gated like every other {@code /api} surface).
 *
 * <p>It reads nothing from the {@link ArtifactStore} - the fleet view is a live API read, not store state - and
 * <strong>degrades cleanly to single-node</strong>: a deployment with one live node shows that node and an explicit
 * "single node - no divergence to check" state, never a false positive, and before a key is entered it shows an empty
 * state rather than an error. It detects and reports; it never blocks. All dynamic text is API-derived and escaped in
 * JS before it reaches the DOM, so a node id or reason cannot inject markup.
 */
public final class ConsistencyPanel implements Panel {

    @Override
    public String id() {
        return "consistency";
    }

    @Override
    public String title() {
        return "Consistency";
    }

    @Override
    public String render(ArtifactStore store) {
        // A self-contained fragment: the shell drops it in unescaped. All dynamic text is API-derived and escaped in JS
        // (jconEsc) before it reaches the DOM, so a node id or divergence reason cannot inject markup.
        return """
                <p>The nodes sharing this store and whether they agree
                (<code>GET /api/consistency</code>). Each node publishes a lightweight fingerprint of its derived state
                (index cursor, config generation, counters, a sampled pointer set) on a heartbeat; the check tells
                benign lag from a node <strong>stuck diverged</strong>. The read is key-gated: paste a credential
                carrying <code>repository:read</code>. It detects and reports - it never blocks - and degrades to
                single-node (one node, no divergence).</p>
                <div class="grid">
                  <label>Key <input id="jcon-key" placeholder="tenant.secret" type="password"></label>
                </div>
                <p>
                  <button class="secondary" onclick="jconLoad()">Refresh</button>
                  <button class="secondary" id="jcon-tail" onclick="jconToggle()">Start auto-refresh</button>
                  <span id="jcon-status"></span>
                </p>
                <div id="jcon-summary"></div>
                <div id="jcon-nodes"></div>
                <div id="jcon-divergences"></div>
                <script>
                (function(){
                  var timer=null;
                  function esc(s){return (s==null?'':String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
                  window.jconEsc=esc;
                  window.jconLoad=function(){
                    var key=document.getElementById('jcon-key').value.trim();
                    var headers=key?{'Jenesis-Repository-Key':key}:{};
                    fetch('/api/consistency',{headers:headers}).then(function(r){
                      if(!r.ok)throw new Error('status '+r.status); return r.json();
                    }).then(function(d){render(d);
                      document.getElementById('jcon-status').textContent=d.liveCount+' live of '+d.nodeCount+' node(s)';
                    }).catch(function(e){document.getElementById('jcon-status').textContent='error: '+e.message;});
                  };
                  function render(d){
                    var sum=document.getElementById('jcon-summary');
                    if(d.singleNode){
                      sum.innerHTML='<p><strong>Single node</strong> - no divergence to check. Local node <code>'+esc(d.localNodeId)+'</code>.</p>';
                    } else if(d.converged){
                      sum.innerHTML='<p><strong>Converged</strong> - '+d.liveCount+' live nodes agree. Local node <code>'+esc(d.localNodeId)+'</code>.</p>';
                    } else {
                      sum.innerHTML='<p><strong>Diverged</strong> - '+d.divergences.length+' finding(s) across '+d.liveCount+' live nodes. Local node <code>'+esc(d.localNodeId)+'</code>.</p>';
                    }
                    var nodes=document.getElementById('jcon-nodes');
                    if(!d.nodes||!d.nodes.length){nodes.innerHTML='<p>No node fingerprints published yet.</p>';}
                    else{
                      var h='<h4>Nodes</h4><table><thead><tr><th>node</th><th>state</th><th>heartbeat age (ms)</th><th>index cursor</th><th>config gen</th><th>quota used</th></tr></thead><tbody>';
                      d.nodes.forEach(function(n){
                        var state=(n.live?'live':'dead')+(n.stale?', stale':'')+(n.local?' (this node)':'');
                        h+='<tr><td><code>'+esc(n.nodeId)+'</code></td><td>'+esc(state)+'</td><td>'+esc(n.heartbeatAgeMillis)+'</td><td>'+esc(n.indexCursor)+'</td><td><code>'+esc(n.configGeneration)+'</code></td><td>'+esc(n.quotaUsed)+'</td></tr>';
                      });
                      nodes.innerHTML=h+'</tbody></table>';
                    }
                    var div=document.getElementById('jcon-divergences');
                    if(!d.divergences||!d.divergences.length){div.innerHTML='';}
                    else{
                      var dh='<h4>Divergences</h4>';
                      d.divergences.forEach(function(x){
                        dh+='<article class="app-card"><header><strong>'+esc(x.kind)+'</strong> node <code>'+esc(x.nodeId)+'</code></header><p>'+esc(x.detail)+'</p></article>';
                      });
                      div.innerHTML=dh;
                    }
                  }
                  window.jconToggle=function(){
                    var b=document.getElementById('jcon-tail');
                    if(timer){clearInterval(timer);timer=null;b.textContent='Start auto-refresh';}
                    else{jconLoad();timer=setInterval(jconLoad,5000);b.textContent='Stop auto-refresh';}
                  };
                })();
                </script>
                """;
    }
}
