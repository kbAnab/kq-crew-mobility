import argparse
from datetime import datetime, time, timedelta, timezone

EAT = timezone(timedelta(hours=3))   


def at(day_offset: int, hhmm: str) -> datetime:
    """Local (EAT) datetime for today + day_offset at HH:MM."""
    today = datetime.now(EAT).date() + timedelta(days=day_offset)
    h, m = map(int, hhmm.split(":"))
    return datetime.combine(today, time(h, m), tzinfo=EAT)


def build_flights() -> dict:
    rows = [
        ("KQ600-CPT", "KQ600", "NBO – MBA", 1, "14:05", "Captain", 1, 0, False),
        ("KQ600-FO", "KQ600", "NBO – MBA", 1, "14:05", "First Officer", 1, 1, False),
        ("KQ600-CC", "KQ600", "NBO – MBA", 1, "14:05", "Cabin Crew", 4, 2, False),
        ("KQ472-CC", "KQ472", "NBO – JRO", 1, "08:30", "Cabin Crew", 4, 4, False),
        ("KQ412-CPT", "KQ412", "NBO – EBB", 2, "14:00", "Captain", 1, 0, False),
        ("KQ100-FO", "KQ100", "NBO – LHR", 2, "22:30", "First Officer", 1, 0, True),
    ]
    return {
        fid: {
            "flightNo": no, "route": route, "departure": at(day, hhmm),
            "qualification": qual, "slots": slots, "filled": filled,
            "international": intl, "status": "open" if filled < slots else "filled",
        }
        for fid, no, route, day, hhmm, qual, slots, filled, intl in rows
    }


def build_crew() -> dict:
    # id, name, qualification, experience, dutyHours, restHours
    rows = [
        ("C0101", "Claus Harder", "Captain", 24, 2.5, 14.0),
        ("C0102", "Wanjiru Kamau", "Captain", 18, 4.0, 13.2),
        ("C0103", "Peter Otieno", "Captain", 12, 6.0, 11.5),
        ("C0104", "Samuel Mwangi", "Captain", 9, 9.0, 8.5),
        ("C0201", "Evelyn Bouchard", "First Officer", 8, 3.0, 15.0),
        ("C0202", "Brian Mutua", "First Officer", 5, 5.5, 12.5),
        ("C0203", "Amina Hassan", "First Officer", 3, 7.0, 10.5),
        ("C0204", "Kevin Omondi", "First Officer", 2, 4.0, 13.0),
        ("C0231", "Phoebe Thompson", "Cabin Crew", 14, 3.0, 14.5),
        ("C0232", "Gabriel Schmitt", "Cabin Crew", 9, 4.5, 13.0),
        ("C0233", "Grace Njeri", "Cabin Crew", 11, 7.5, 10.8),
        ("C0234", "Lin Koyama", "Cabin Crew", 4, 5.0, 12.0),
        ("C0235", "Faith Achieng", "Cabin Crew", 2, 2.0, 14.0),
        ("C0236", "Daniel Kiptoo", "Cabin Crew", 7, 6.5, 11.2),
        ("C0237", "Mercy Wafula", "Cabin Crew", 6, 11.0, 9.0),
    ]
    return {
        cid: {
            "name": name, "qualification": qual,
            "crewType": "Cabin Crew" if qual == "Cabin Crew" else "Pilot",
            "experienceYears": exp, "flightHours": exp * 480,
            "dutyHours": duty, "restHours": rest,
            "available": True, "active": True,
        }
        for cid, name, qual, exp, duty, rest in rows
    }


def build_alerts() -> dict:
    rows = [
        ("a1", "Duty limit exceeded", "FO Evelyn Bouchard is 1.5 h over the planned duty limit on KQ600.", "HIGH"),
        ("a2", "Crew no-show", "FA Gabriel Schmitt has not checked in for KQ472.", "HIGH"),
        ("a3", "Flight delay", "KQ412 NBO – EBB delayed by 55 minutes; crew duty time affected.", "MEDIUM"),
        ("a4", "Standby crew low", "Only 2 cabin crew on standby at NBO tonight.", "MEDIUM"),
        ("a5", "Roster published", "Weekly roster is ready for review.", "LOW"),
    ]
    now = datetime.now(EAT)
    return {
        i: {"title": t, "detail": d, "severity": sev, "acknowledged": False,
            "createdAt": now - timedelta(minutes=30 * n)}
        for n, (i, t, d, sev) in enumerate(rows)
    }


def build_compliance() -> dict:
    rows = [
        ("k1", "C0201", "Evelyn Bouchard (FO)", "Proficiency check", "Expired", "BREACH"),
        ("k2", "C0237", "Mercy Wafula (FA)", "Rest before duty", "Below the internal minimum", "BREACH"),
        ("k3", "C0101", "Claus Harder (Captain)", "Medical certificate", "Expires soon", "DUE_SOON"),
        ("k4", "C0103", "Peter Otieno (Captain)", "Recurrent training", "Due soon", "DUE_SOON"),
        ("k5", "C0104", "Samuel Mwangi (Captain)", "Licence validity", "Due soon", "DUE_SOON"),
        ("k6", "C0234", "Lin Koyama (FA)", "Safety equipment training", "Valid", "OK"),
        ("k7", "C0231", "Phoebe Thompson (FA)", "Medical certificate", "Valid", "OK"),
        ("k8", "C0202", "Brian Mutua (FO)", "Proficiency check", "Valid", "OK"),
    ]
    return {i: {"crewId": cid, "crew": crew, "check": check, "detail": detail, "status": status}
            for i, cid, crew, check, detail, status in rows}


def build_notifications() -> dict:
    now = datetime.now(EAT)
    rows = [
        ("n1", "Roster change", "KQ472 NBO – JRO now departs 08:30.", False),
        ("n2", "Duty reminder", "Report for KQ600 NBO – MBA at 12:35.", False),
        ("n3", "Assignment approved", "You were confirmed on KQ412 NBO – EBB.", True),
        ("n4", "Training due", "Safety equipment training expires in 30 days.", True),
    ]
    return {i: {"userId": "all", "title": t, "message": m, "read": r,
                "createdAt": now - timedelta(hours=n)} for n, (i, t, m, r) in enumerate(rows)}


def build_assignments() -> dict:
    now = datetime.now(EAT)
    return {
        "as1": {"crewId": "C0102", "crewName": "Wanjiru Kamau", "crewUid": "", "flightId": "KQ412-CPT",
                "status": "pending", "source": "Manual request", "requestedAt": now},
        "as2": {"crewId": "C0233", "crewName": "Grace Njeri", "crewUid": "", "flightId": "KQ600-CC",
                "status": "pending", "source": "AI recommender", "requestedAt": now},
    }


def build_policies() -> dict:
    return {"policies": {
        "domestic": {"maxDutyHours": 12, "minRestHours": 10, "minExperienceYears": 1},
        "international": {"maxDutyHours": 11, "minRestHours": 12, "minExperienceYears": 3},
    }}


def build_duties() -> dict:
    rows = [
        (0, "d0a", "FLIGHT", "NBO – MBA", "14:05", "15:15", "KQ600 / 5Y-KYA"),
        (0, "d0b", "FLIGHT", "MBA – NBO", "16:00", "17:10", "KQ601 / 5Y-KYA"),
        (1, "d1a", "FLIGHT", "NBO – JRO", "08:30", "09:35", "KQ472 / 5Y-KZB"),
        (1, "d1b", "FLIGHT", "JRO – NBO", "10:20", "11:25", "KQ473 / 5Y-KZB"),
        (1, "d1c", "FLIGHT", "NBO – EBB", "14:00", "15:20", "KQ412 / 5Y-KZB"),
        (2, "d2a", "RESERVE", "RESV", "00:00", "23:59", ""),
        (3, "d3a", "OFF", "OFF", "00:00", "23:59", ""),
        (4, "d4a", "OFF", "OFF", "00:00", "23:59", ""),
        (5, "d5a", "FLIGHT", "NBO – LHR", "22:30", "06:15", "KQ100 / 5Y-KZC"),
    ]
    out = {}
    for day, did, typ, title, s, e, info in rows:
        start = at(day, s)
        end = at(day, e)
        if end <= start:                    
            end += timedelta(days=1)
        out[did] = {"type": typ, "title": title, "time": f"{s} - {e}", "flightInfo": info,
                    "start": start, "end": end}
    return out


def main():
    parser = argparse.ArgumentParser(description="Seed Firestore with demo data")
    parser.add_argument("--email", help="existing account that receives the sample duties")
    parser.add_argument("--dry-run", action="store_true", help="print what would be written")
    parser.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    args = parser.parse_args()

    collections = {
        "flights": build_flights(), "crew": build_crew(), "alerts": build_alerts(),
        "compliance": build_compliance(), "notifications": build_notifications(),
        "assignments": build_assignments(), "settings": build_policies(),
    }

    uid = None
    if args.email and not args.dry_run:
        from firebase_admin import auth
        from services.firebase_service import get_db
        get_db()                                    # initialise the Admin SDK
        try:
            uid = auth.get_user_by_email(args.email).uid
        except Exception as e:
            raise SystemExit(f"Could not find an account for {args.email}: {e}")
        collections[f"users/{uid}/duties"] = build_duties()
    elif args.email:
        collections["users/<uid of --email>/duties"] = build_duties()

    total = sum(len(v) for v in collections.values())
    print(f"{total} documents in {len(collections)} collections:")
    for name, docs in collections.items():
        print(f"  {name:32s} {len(docs):3d}  e.g. {next(iter(docs))}")

    if args.dry_run:
        print("Dry run: nothing written.")
        return
    if not args.email:
        print("Note: no --email given, so no schedule duties will be created.")
    if not args.yes and input("Write to Firestore now? [y/N] ").strip().lower() != "y":
        raise SystemExit("Cancelled.")

    from services.firebase_service import get_db
    db = get_db()
    batch = db.batch()
    count = 0
    for path, docs in collections.items():
        for doc_id, data in docs.items():
            batch.set(db.collection(path).document(doc_id), data)
            count += 1
            if count % 400 == 0:                   
                batch.commit()
                batch = db.batch()
    batch.commit()
    print(f"Done: {count} documents written.")


if __name__ == "__main__":
    main()
