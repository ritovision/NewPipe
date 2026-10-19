#!/usr/bin/env python3
"""Independent Python netstring oracle. Explicitly regenerate; tests never rewrite expectations."""
# SPDX-License-Identifier: GPL-3.0-or-later
from copy import deepcopy
import hashlib
import json
from pathlib import Path


def encode(c):
    parts = [b"rv-cloud/1\n"]
    def token(v):
        b = str(v).encode("utf-8")
        parts.append(str(len(b)).encode("ascii") + b":" + b + b",")
    def key(k):
        token(k["service"]); token(k["url"])
    def keys(seq):
        token(len(seq))
        for k in seq: key(k)
    def nullable(value):
        token(0 if value is None else 1)
        if value is not None: token(value)
    token(c["version"]); token(c["serviceMap"])
    token("library"); keys(c["library"])
    token("unavailable"); keys(c["unavailable"])
    token("playlists"); token(len(c["playlists"]))
    for p in c["playlists"]:
        token(p["id"]); nullable(p["name"]); token(0 if p["artwork"] is None else 1)
        if p["artwork"] is not None: key(p["artwork"])
        keys(p["items"])
    for section in ("categories", "tags"):
        token(section); token(len(c[section]))
        for d in sorted(c[section], key=lambda d: d["id"]):
            token(d["id"]); token(d["name"]); token(d["nameKey"])
            if section == "tags": nullable(d["category"])
    assignments = sorted((a for a in c["assignments"] if a["tags"]),
                         key=lambda a: (a["key"]["service"], a["key"]["url"].encode("utf-16-be")))
    token("assignments"); token(len(assignments))
    for a in assignments:
        key(a["key"]); token(len(a["tags"]))
        for t in sorted(a["tags"]): token(t)
    return b"".join(parts)


def main():
    a = {"service": 0, "url": "https://example.test/A"}
    b = {"service": 1, "url": "https://example.test/β?x=1&x=2"}
    x = {"service": 0, "url": "https://example.test/not-saved"}
    p = "30000000-0000-0000-0000-000000000001"
    cat = "10000000-0000-0000-0000-000000000001"
    t1 = "20000000-0000-0000-0000-000000000001"
    t2 = "20000000-0000-0000-0000-000000000002"
    empty = dict(version=1, serviceMap=1, library=[], unavailable=[], playlists=[], categories=[], tags=[], assignments=[])
    base = dict(version=1, serviceMap=1, library=[a,b], unavailable=[a],
                playlists=[dict(id=p, name="Mix", artwork=a, items=[b,a,a])],
                categories=[dict(id=cat, name="Genre", nameKey="genre")],
                tags=[dict(id=t1, name="Jazz", nameKey="jazz", category=cat),
                      dict(id=t2, name="İSTANBUL", nameKey="i̇stanbul", category=None)],
                assignments=[dict(key=a, tags=[t2,t1])])
    catalogs = {"empty": empty, "base": base}
    c = deepcopy(base); c["tags"].reverse(); c["assignments"][0]["tags"].reverse(); c["assignments"].append(dict(key=b,tags=[])); catalogs["semantic-order"] = c
    c = deepcopy(base); c["library"].reverse(); c["playlists"][0]["items"] = [a,b,a]; catalogs["reorder"] = c
    c = deepcopy(base); c["library"] = [b]; c["unavailable"] = []; c["playlists"][0]["items"] = [b]; c["assignments"] = []; catalogs["global-remove"] = c
    c = deepcopy(base); c["playlists"][0]["name"] = "Mix renamed"; catalogs["rename"] = c
    c = deepcopy(base); c["categories"] = []; c["tags"] = []; c["assignments"] = []; catalogs["category-delete"] = c
    c = deepcopy(base); c["playlists"][0]["name"] = None; c["playlists"][0]["artwork"] = x; catalogs["nullable-and-external-artwork"] = c
    normalization = [
        ["  Jazz  ", "jazz"], ["\u001c\u00a0Case\u3000", "case"],
        ["İSTANBUL", "i̇stanbul"], ["ΟΣ", "ος"], ["𐐀", "𐐨"],
        ["A👩\u200d💻", "a👩\u200d💻"], ["\ufeffOld", "\ufeffold"],
        ["old\u0001name", "old\u0001name"], ["\u0085Old\u0085", "\u0085old\u0085"],
        ["\u2000\u202fName\u205f", "name"], ["", ""]]
    c = deepcopy(empty)
    c["library"] = [dict(service=0,url="https://example.test/𐐀"),dict(service=0,url="https://example.test/\ue000")]
    c["tags"] = [dict(id=f"20000000-0000-0000-0000-{i:012d}",name=n,nameKey=k,category=None) for i,(n,k) in enumerate(normalization,1)]
    c["assignments"] = [dict(key=k,tags=[c["tags"][0]["id"]]) for k in reversed(c["library"])]
    catalogs["unicode-legacy"] = c
    invalid = []
    def bad(name, edit):
        c = deepcopy(base); edit(c); invalid.append(dict(name=name, catalog=c))
    bad("duplicate-library", lambda c: c["library"].append(a))
    bad("outside-unavailable", lambda c: c["unavailable"].append(x))
    bad("reserved-name", lambda c: c["playlists"][0].update(name="\u00a0UNAVAILABLE\u2003"))
    bad("orphan-assignment", lambda c: c["assignments"].append(dict(key=x,tags=[t1])))
    bad("codec-version", lambda c: c.update(version=2))
    bad("unsupported-field", lambda c: c.update(nativeRowId=12))
    bad("wrong-name-key", lambda c: c["tags"][0].update(nameKey="JAZZ"))
    bad("duplicate-tag-id", lambda c: c["tags"][1].update(id=t1))
    bad("unknown-category", lambda c: c["tags"][0].update(category=p))
    bad("outside-playlist", lambda c: c["playlists"][0]["items"].append(x))
    bad("duplicate-playlist-id", lambda c: c["playlists"].append(deepcopy(c["playlists"][0])))
    bad("duplicate-assignment", lambda c: c["assignments"].append(deepcopy(c["assignments"][0])))
    bad("duplicate-assigned-tag", lambda c: c["assignments"][0]["tags"].append(t1))
    bad("unpaired-surrogate", lambda c: c["playlists"][0].update(name="\ud800"))
    golden = []
    for name,c in catalogs.items():
        payload = encode(c)
        golden.append(dict(name=name,catalog=c,sha256=hashlib.sha256(payload).hexdigest(),bytes=len(payload)))
    hashes = {g["name"]:g["sha256"] for g in golden}
    head = dict(serviceGeneration="service-a",library="library-a",epoch="epoch-a",revision="10",headCounter="10",fingerprint=hashes["base"])
    remote = {**head,"revision":"11","headCounter":"11","fingerprint":hashes["reorder"]}
    default = dict(enabled=True,identityReady=True,unresolvedOperation=False,localFingerprint=hashes["base"],baseline=head,remote=head,highestObserved=head)
    policies=[]
    def policy(name,expected,**changes): policies.append(dict(name=name,expected=expected,input={**deepcopy(default),**changes}))
    policy("disabled-never-needs-network","DISABLED",enabled=False,remote=None,unresolvedOperation=True)
    policy("identity-repair","IDENTITY_REPAIR_REQUIRED",identityReady=False,remote=None)
    policy("enrollment","ENROLLMENT_REQUIRED",baseline=None,highestObserved=None)
    policy("unchanged","NOOP")
    policy("local-only","PUSH",localFingerprint=hashes["rename"])
    policy("cloud-only","PULL",remote=remote)
    policy("both-diverged","CONFLICT",localFingerprint=hashes["rename"],remote=remote)
    policy("equal-after-divergence","ADOPT_BASELINE",localFingerprint=hashes["reorder"],remote=remote)
    policy("unknown-result-first","RESOLVE_OPERATION",unresolvedOperation=True)
    policy("counter-regressed","ROLLBACK_SUSPECTED",remote={**head,"headCounter":"9","revision":"9"})
    policy("old-epoch-counter","ROLLBACK_SUSPECTED",remote={**head,"epoch":"old-epoch","headCounter":"9"})
    policy("same-counter-new-epoch","ROLLBACK_SUSPECTED",remote={**head,"epoch":"new-epoch"})
    policy("same-counter-other-content","ROLLBACK_SUSPECTED",remote={**head,"fingerprint":hashes["rename"]})
    policy("same-epoch-revision-regressed","ROLLBACK_SUSPECTED",remote={**remote,"revision":"9"})
    policy("service-reset","REENROLL_REQUIRED",remote={**head,"serviceGeneration":"service-b"})
    policy("other-library","REENROLL_REQUIRED",remote={**head,"library":"library-b"})
    policy("clean-new-epoch","PULL",remote={**remote,"epoch":"epoch-b","revision":"0"})
    policy("dirty-new-epoch","CONFLICT",remote={**remote,"epoch":"epoch-b","revision":"0"},localFingerprint=hashes["rename"])
    policy("highest-head-regression","ROLLBACK_SUSPECTED",remote=remote,highestObserved={**remote,"headCounter":"12","revision":"12"})
    largehead = {**head,"headCounter":"9007199254740992"}
    policy("above-js-safe-integer","PULL",baseline=largehead,highestObserved=largehead,remote={**remote,"headCounter":"9007199254740993"})
    diffs = [
      dict(before="empty",after="base",added=2,removed=0,playlists=1,occurrencesAdded=3,occurrencesRemoved=0,tagsAdded=2,tagsRemoved=0,categoriesRemoved=0,assignments=1),
      dict(before="base",after="semantic-order",added=0,removed=0,playlists=0,occurrencesAdded=0,occurrencesRemoved=0,tagsAdded=0,tagsRemoved=0,categoriesRemoved=0,assignments=0),
      dict(before="base",after="global-remove",added=0,removed=1,playlists=1,occurrencesAdded=0,occurrencesRemoved=2,tagsAdded=0,tagsRemoved=0,categoriesRemoved=0,assignments=1),
      dict(before="base",after="rename",added=0,removed=0,playlists=1,occurrencesAdded=0,occurrencesRemoved=0,tagsAdded=0,tagsRemoved=0,categoriesRemoved=0,assignments=0),
      dict(before="base",after="reorder",added=0,removed=0,playlists=1,occurrencesAdded=0,occurrencesRemoved=0,tagsAdded=0,tagsRemoved=0,categoriesRemoved=0,assignments=0),
      dict(before="base",after="category-delete",added=0,removed=0,playlists=0,occurrencesAdded=0,occurrencesRemoved=0,tagsAdded=0,tagsRemoved=2,categoriesRemoved=1,assignments=1)]
    output = dict(oracle="Independent Python UTF-8 netstring encoder; synthetic example.test metadata only",catalogs=golden,invalid=invalid,normalization=normalization,
                  validCounters=["0","1","9007199254740991","9007199254740992","9007199254740993","9223372036854775807"],
                  invalidCounters=["","-1","+1","01","-0","1.0","1e3"," 1","1\n","9223372036854775808","18446744073709551615"],policies=policies,diffs=diffs)
    target = Path(__file__).resolve().parents[2]/"app/src/test/resources/cloud/golden-v1.json"
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(output,ensure_ascii=True,indent=2)+"\n")
    print("GOLDEN",len(golden),"INVALID",len(invalid),"POLICY",len(policies),"DIFF",len(diffs),"BYTES",target.stat().st_size)

if __name__ == "__main__": main()
