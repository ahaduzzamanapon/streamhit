import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import asyncio, time, json
from main import request_moviebox_mobile, _make_request, _fetch_download_resources

def extract_from_detectors(detectors):
    items = []
    for det in detectors:
        res_list = det.get("resolutionList", [])
        if res_list:
            for r in res_list:
                r_link = r.get("resourceLink")
                if r_link and not any(bad in r_link for bad in ["fzmovies", "southfreak"]):
                    items.append({
                        "resourceId": str(r.get("resourceId") or r.get("id")),
                        "resolution": int(r.get("resolution", 720)),
                        "size": int(r.get("size", 0)),
                        "resourceLink": f"/fetch?source_url={r_link}"
                    })
        elif det.get("resourceLink") and not any(bad in det.get("resourceLink", "") for bad in ["fzmovies", "southfreak"]):
            items.append({
                "resourceId": str(det.get("resourceId") or "0"),
                "resolution": 720,
                "size": int(det.get("totalSize") or det.get("firstSize") or 0),
                "resourceLink": f"/fetch?source_url={det['resourceLink']}"
            })
    return items

async def test_fast_resource(subjectId, se=1, ep=1, detailPath=""):
    t0 = time.time()
    # Concurrently fetch H5 and Mobile
    h5_task = asyncio.create_task(_fetch_download_resources(subjectId, se, ep, detailPath))
    mob_task = asyncio.create_task(request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": subjectId, "se": se, "ep": ep}))
    
    h5_data, mob_res = await asyncio.gather(h5_task, mob_task, return_exceptions=True)
    
    # 1. H5 downloads
    if isinstance(h5_data, dict) and h5_data.get("downloads"):
        items = [{"url": d.get("url"), "res": d.get("resolution")} for d in h5_data["downloads"] if d.get("url")]
        print(f"Got {len(items)} items from H5 in {round(time.time() - t0, 2)}s")
        return items
        
    # 2. Mobile detectors
    mob_subj = {}
    if isinstance(mob_res, dict):
        mob_subj = mob_res.get("data", {}).get("subject") or mob_res.get("data") or {}
        
    detectors = mob_subj.get("resourceDetectors") or []
    det_items = extract_from_detectors(detectors)
    if det_items:
        print(f"Got {len(det_items)} items from own mobile detectors in {round(time.time() - t0, 2)}s")
        return det_items
        
    # 3. Sibling/Original audio fallback
    dubs = mob_subj.get("dubs") or []
    orig = next((d for d in dubs if d.get("original") and str(d.get("subjectId")) != str(subjectId)), None)
    if orig:
        orig_id = str(orig.get("subjectId"))
        print(f"Checking original dub ({orig_id})...")
        orig_mob = await request_moviebox_mobile("/wefeed-mobile-bff/subject-api/get", params={"subjectId": orig_id, "se": se, "ep": ep})
        orig_subj = orig_mob.get("data", {}).get("subject") or orig_mob.get("data") or {}
        orig_items = extract_from_detectors(orig_subj.get("resourceDetectors") or [])
        if orig_items:
            print(f"Got {len(orig_items)} items from original audio in {round(time.time() - t0, 2)}s")
            return orig_items
            
    print(f"Failed in {round(time.time() - t0, 2)}s")
    return []

if __name__ == "__main__":
    sid = "357902969481872240"
    slug = "extraction-hindi-qV2yXtmcrq"
    asyncio.run(test_fast_resource(sid, 1, 1, slug))
