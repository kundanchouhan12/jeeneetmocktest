import os
import sys
import time
from datetime import datetime, timedelta
import pandas as pd
import numpy as np
import plotly.express as px
import plotly.graph_objects as go
import streamlit as st

import firebase_admin
from firebase_admin import credentials, firestore

# -----------------------------------------------------------------------------
# 1. PAGE CONFIGURATION & STYLING
# -----------------------------------------------------------------------------
st.set_page_config(
    page_title="MockTestApp Universal Insights Dashboard",
    page_icon="⚡",
    layout="wide",
    initial_sidebar_state="expanded"
)

st.markdown("""
<style>
    .main-header {
        font-size: 2.2rem;
        font-weight: 700;
        background: linear-gradient(90deg, #4F46E5 0%, #7C3AED 100%);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        margin-bottom: 0.5rem;
    }
    .metric-card {
        background-color: #1E1E2E;
        padding: 1.2rem;
        border-radius: 0.8rem;
        border: 1px solid #313244;
        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
    }
</style>
""", unsafe_allow_html=True)

# -----------------------------------------------------------------------------
# 2. FIREBASE ADMIN INITIALIZATION
# -----------------------------------------------------------------------------
@st.cache_resource
def init_firebase():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)
    creds_path = os.path.join(project_dir, "scripts", "serviceAccountKey.json")
    
    if not os.path.exists(creds_path):
        st.error(f"❌ serviceAccountKey.json not found at `{creds_path}`")
        st.stop()
        
    if not firebase_admin._apps:
        cred = credentials.Certificate(creds_path)
        firebase_admin.initialize_app(cred)
    
    return firestore.client()

try:
    db = init_firebase()
except Exception as e:
    st.error(f"Failed to connect to Firebase Firestore: {e}")
    st.stop()

# -----------------------------------------------------------------------------
# 3. UNIVERSAL DATA FETCHING (ALL APP VERSIONS)
# -----------------------------------------------------------------------------
@st.cache_data(ttl=30)
def fetch_metadata():
    metadata = {}
    try:
        bank_doc = db.collection("metadata").document("question_bank").get()
        if bank_doc.exists:
            metadata["bank"] = bank_doc.to_dict()
            
        health_doc = db.collection("metadata").document("daily_health_summary").get()
        if health_doc.exists:
            metadata["health"] = health_doc.to_dict()
            
        power100_doc = db.collection("metadata").document("power100_status").get()
        if power100_doc.exists:
            metadata["power100"] = power100_doc.to_dict()
            
        app_ver_doc = db.collection("metadata").document("app_version").get()
        if app_ver_doc.exists:
            metadata["app_version"] = app_ver_doc.to_dict()
            
        exam_dates_doc = db.collection("metadata").document("exam_dates").get()
        if exam_dates_doc.exists:
            metadata["exam_dates"] = exam_dates_doc.to_dict()
    except Exception as e:
        st.warning(f"Error fetching metadata: {e}")
    return metadata

@st.cache_data(ttl=60)
def fetch_questions_summary():
    try:
        docs = db.collection("questions").select([
            "examType", "subject", "chapter", "questionMode", "sourceType", "createdAt"
        ]).get()
        
        data = []
        for d in docs:
            doc_data = d.to_dict()
            doc_id = d.id
            is_vault = doc_id.startswith("vault_")
            
            data.append({
                "id": doc_id,
                "is_vault": is_vault,
                "examType": doc_data.get("examType", "Unknown"),
                "subject": doc_data.get("subject", "Unknown"),
                "chapter": doc_data.get("chapter", "Unassigned"),
                "questionMode": doc_data.get("questionMode", "TEXT"),
                "sourceType": doc_data.get("sourceType", "PRACTICE"),
                "createdAt": doc_data.get("createdAt", None)
            })
        return pd.DataFrame(data)
    except Exception as e:
        st.error(f"Error fetching questions: {e}")
        return pd.DataFrame()

# UNIVERSAL DATA FETCH (PULLS FROM ALL APP VERSIONS ACROSS ALL USERS)
@st.cache_data(ttl=15)
def fetch_universal_user_analytics():
    # 1. Query 'users' collection (Written by all app versions)
    try:
        user_docs = db.collection("users").get()
        users_list = []
        scans_today_total = 0
        ad_refills_today_total = 0
        
        for doc in user_docs:
            d = doc.to_dict()
            created_ts = d.get("createdAt", 0)
            created_dt = None
            created_str = "N/A"
            
            if isinstance(created_ts, (int, float)) and created_ts > 0:
                try:
                    created_dt = datetime.fromtimestamp(created_ts / 1000.0)
                    created_str = created_dt.strftime("%Y-%m-%d %H:%M")
                except Exception:
                    pass
            elif isinstance(created_ts, datetime):
                created_dt = created_ts
                created_str = created_dt.strftime("%Y-%m-%d %H:%M")
                
            scans_used = d.get("scans_used_today", 0)
            ad_refills = d.get("ad_refills_today", 0)
            scans_today_total += scans_used
            ad_refills_today_total += ad_refills
            
            users_list.append({
                "uid": d.get("uid", doc.id),
                "username": d.get("username", "Anonymous Student"),
                "email": d.get("email", "No Email"),
                "has_fcm": bool(d.get("fcmTokens")),
                "scans_today": scans_used,
                "ad_refills_today": ad_refills,
                "terms_accepted": d.get("termsAccepted", False),
                "created_str": created_str,
                "created_dt": created_dt,
                "created_date_only": created_dt.strftime("%Y-%m-%d") if created_dt else "Unknown"
            })
        df_users = pd.DataFrame(users_list)
    except Exception as e:
        df_users = pd.DataFrame()
        scans_today_total = 0
        ad_refills_today_total = 0
        
    # 2. Query 'dailyStats' collection (Written by all app versions)
    try:
        stats_docs = db.collection("dailyStats").get()
        stats_list = []
        total_attempts = 0
        for doc in stats_docs:
            d = doc.to_dict()
            attempts = d.get("attemptCount", 0)
            total_attempts += attempts
            stats_list.append({
                "date_key": doc.id,
                "attemptCount": attempts
            })
        df_stats = pd.DataFrame(stats_list)
    except Exception:
        df_stats = pd.DataFrame()
        total_attempts = 0
        
    fcm_count = len(df_users[df_users["has_fcm"] == True]) if not df_users.empty and "has_fcm" in df_users.columns else 0
    
    return {
        "total_users": len(df_users),
        "fcm_count": fcm_count,
        "scans_today_total": scans_today_total,
        "ad_refills_today_total": ad_refills_today_total,
        "total_attempts": total_attempts,
        "df_users": df_users,
        "df_stats": df_stats
    }

# -----------------------------------------------------------------------------
# 4. SIDEBAR CONTROLS & AUTO REFRESH
# -----------------------------------------------------------------------------
st.sidebar.image("https://img.icons8.com/color/96/dashboard-layout.png", width=64)
st.sidebar.title("Universal Insights")

auto_refresh = st.sidebar.checkbox("⚡ Auto-refresh (30s)", value=False)
if auto_refresh:
    time.sleep(30)
    st.rerun()

if st.sidebar.button("🔄 Refresh Data Now"):
    st.cache_data.clear()
    st.rerun()

st.sidebar.markdown("---")
st.sidebar.info("Connected to Firestore database: **apps-273d9**")

meta = fetch_metadata()
df_q = fetch_questions_summary()
universal_data = fetch_universal_user_analytics()

# -----------------------------------------------------------------------------
# 5. DASHBOARD HEADER & KPI METRICS
# -----------------------------------------------------------------------------
st.markdown('<div class="main-header">⚡ MockTestApp Universal Analytics Dashboard</div>', unsafe_allow_html=True)
st.caption(f"Last updated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} IST | Universal Firestore Analytics (All App Versions)")

col1, col2, col3, col4, col5 = st.columns(5)

bank_info = meta.get("bank", {})
total_q = len(df_q) if not df_q.empty else bank_info.get("totalQuestions", 0)

with col1:
    st.metric(label="Total Bank Questions", value=f"{total_q:,}", delta="Live Firestore Count")

with col2:
    st.metric(label="Registered Users (All Versions)", value=f"{universal_data['total_users']:,}", delta="100% User Base")

with col3:
    st.metric(label="Push Broadcast Reach", value=f"{universal_data['fcm_count']:,}", delta="Active Push Tokens")

with col4:
    st.metric(label="AI Doubt Scans Today", value=f"{universal_data['scans_today_total']:,}", delta="Scans Used Today")

with col5:
    app_ver = meta.get("app_version", {}).get("latest_version", "1.0.0")
    st.metric(label="App Release Version", value=f"v{app_ver}", delta="Active in Play Store")

st.markdown("---")

# -----------------------------------------------------------------------------
# 6. TABS LAYOUT
# -----------------------------------------------------------------------------
tab1, tab2, tab3, tab4, tab5 = st.tabs([
    "🌐 Universal Insights (All App Versions)",
    "📊 Question Bank Metrics", 
    "📚 Syllabus & Chapter Density", 
    "⚡ Daily Automation Health", 
    "🔍 Live Question Inspector"
])

# -----------------------------------------------------------------------------
# TAB 1: UNIVERSAL INSIGHTS (ALL APP VERSIONS)
# -----------------------------------------------------------------------------
with tab1:
    st.subheader("🌐 Real-Time Insights Across ALL Installed App Versions")
    st.info("ℹ️ **Zero App Update Needed**: This panel extracts real telemetry directly from production database collections (`users`, `dailyStats`). It aggregates activity across 100% of your user base regardless of which app version they are running.")
    
    m1, m2, m3, m4 = st.columns(4)
    with m1:
        st.metric(label="Total User Base", value=f"{universal_data['total_users']:,}", delta="Registered Accounts")
    with m2:
        st.metric(label="Push Notification Reach", value=f"{universal_data['fcm_count']:,}", delta="FCM Token Saved")
    with m3:
        st.metric(label="AI Scans Used Today", value=f"{universal_data['scans_today_total']:,}", delta="Active Scans")
    with m4:
        st.metric(label="Ad Refills Today", value=f"{universal_data['ad_refills_today_total']:,}", delta="Rewarded Ads")
        
    st.markdown("---")
    
    c_registrations, c_stats = st.columns(2)
    
    with c_registrations:
        st.markdown("### 📈 User Registrations Timeline (All Versions)")
        df_users = universal_data["df_users"]
        if not df_users.empty and "created_date_only" in df_users.columns:
            reg_counts = df_users[df_users["created_date_only"] != "Unknown"]["created_date_only"].value_counts().reset_index()
            reg_counts.columns = ["date", "registrations"]
            reg_counts = reg_counts.sort_values(by="date")
            
            fig_reg = px.line(
                reg_counts,
                x="date",
                y="registrations",
                markers=True,
                title="Daily New Student Registrations",
                color_discrete_sequence=["#4F46E5"]
            )
            st.plotly_chart(fig_reg, use_container_width=True)
        else:
            st.info("No registration dates recorded yet.")
            
    with c_stats:
        st.markdown("### 🏆 Student Daily Practice Activity (`dailyStats`)")
        df_stats = universal_data["df_stats"]
        if not df_stats.empty and "attemptCount" in df_stats.columns:
            df_stats_sorted = df_stats.sort_values(by="date_key", ascending=False).head(15)
            fig_stats = px.bar(
                df_stats_sorted,
                x="date_key",
                y="attemptCount",
                title="Recent Daily Question Attempts",
                color="attemptCount",
                color_continuous_scale="Purples"
            )
            st.plotly_chart(fig_stats, use_container_width=True)
        else:
            st.info("No practice stats recorded yet.")
            
    st.markdown("---")
    
    # Universal User Search & Table
    st.markdown("### 📋 Complete Registered Student Directory (All Versions)")
    if not df_users.empty:
        search_query = st.text_input("Search student by Username or Email:", value="")
        df_filtered_users = df_users.copy()
        
        if search_query:
            df_filtered_users = df_filtered_users[
                df_filtered_users["username"].str.contains(search_query, case=False, na=False) |
                df_filtered_users["email"].str.contains(search_query, case=False, na=False)
            ]
            
        df_display = df_filtered_users.sort_values(by="created_dt", ascending=False)[
            ["username", "email", "has_fcm", "scans_today", "ad_refills_today", "created_str", "uid"]
        ]
        
        st.dataframe(
            df_display.rename(columns={
                "username": "Student Name",
                "email": "Email Address",
                "has_fcm": "Push Token Active",
                "scans_today": "AI Scans Today",
                "ad_refills_today": "Ad Refills Today",
                "created_str": "Registration Timestamp",
                "uid": "Firebase UID"
            }),
            use_container_width=True,
            height=400
        )

# -----------------------------------------------------------------------------
# TAB 2: QUESTION BANK METRICS
# -----------------------------------------------------------------------------
with tab2:
    st.subheader("Question Bank & Exam Distribution")
    
    if not df_q.empty:
        c1, c2, c3 = st.columns(3)
        
        with c1:
            fig_exam = px.pie(
                df_q, 
                names="examType", 
                title="Exam Allocation (JEE vs NEET)",
                color_discrete_sequence=["#6366F1", "#10B981"],
                hole=0.4
            )
            fig_exam.update_layout(margin=dict(t=40, b=20, l=20, r=20))
            st.plotly_chart(fig_exam, use_container_width=True)
            
        with c2:
            subj_counts = df_q["subject"].value_counts().reset_index()
            subj_counts.columns = ["subject", "count"]
            fig_subj = px.bar(
                subj_counts,
                x="subject",
                y="count",
                color="subject",
                title="Subject Breakdown",
                color_discrete_sequence=px.colors.qualitative.Set2
            )
            fig_subj.update_layout(margin=dict(t=40, b=20, l=20, r=20), showlegend=False)
            st.plotly_chart(fig_subj, use_container_width=True)
            
        with c3:
            mode_counts = df_q["questionMode"].value_counts().reset_index()
            mode_counts.columns = ["questionMode", "count"]
            fig_mode = px.pie(
                mode_counts,
                names="questionMode",
                values="count",
                title="Question Mode Distribution",
                color_discrete_sequence=px.colors.sequential.RdBu,
                hole=0.4
            )
            fig_mode.update_layout(margin=dict(t=40, b=20, l=20, r=20))
            st.plotly_chart(fig_mode, use_container_width=True)

# -----------------------------------------------------------------------------
# TAB 3: SYLLABUS & CHAPTER DENSITY
# -----------------------------------------------------------------------------
with tab3:
    st.subheader("NCERT Chapter-wise Question Coverage")
    
    if not df_q.empty:
        selected_exam = st.selectbox("Select Exam Filter:", ["All", "JEE", "NEET"])
        selected_subj = st.selectbox("Select Subject Filter:", ["All"] + list(df_q["subject"].unique()))
        
        filtered_df = df_q.copy()
        if selected_exam != "All":
            filtered_df = filtered_df[filtered_df["examType"] == selected_exam]
        if selected_subj != "All":
            filtered_df = filtered_df[filtered_df["subject"] == selected_subj]
            
        ch_counts = filtered_df["chapter"].value_counts().head(20).reset_index()
        ch_counts.columns = ["chapter", "count"]
        
        fig_ch = px.bar(
            ch_counts,
            x="count",
            y="chapter",
            orientation="h",
            title="Top 20 Chapters by Available Questions",
            color="count",
            color_continuous_scale="Viridis"
        )
        fig_ch.update_layout(yaxis=dict(autorange="reversed"), height=600)
        st.plotly_chart(fig_ch, use_container_width=True)

# -----------------------------------------------------------------------------
# TAB 4: DAILY AUTOMATION HEALTH
# -----------------------------------------------------------------------------
with tab4:
    st.subheader("⚡ Daily Pipeline Execution & Vault Health")
    
    health_data = meta.get("health", {})
    p100_data = meta.get("power100", {})
    
    c1, c2 = st.columns(2)
    
    with c1:
        st.markdown("### 📋 Automation Summary")
        if health_data:
            st.json(health_data)
        else:
            st.info("No daily health summary recorded yet. Run `python scripts/run_daily_automation.py` to trigger automation.")
            
    with c2:
        st.markdown("### 🏆 Power 100 Live Status")
        if p100_data:
            st.json(p100_data)
        else:
            st.info("Power 100 status metadata is active.")

# -----------------------------------------------------------------------------
# TAB 5: LIVE QUESTION INSPECTOR
# -----------------------------------------------------------------------------
with tab5:
    st.subheader("🔍 Real-Time Question Inspector & KaTeX Preview")
    
    search_query = st.text_input("Search questions by ID or Chapter:", value="")
    
    if st.button("Fetch Sample Live Questions"):
        with st.spinner("Fetching questions from Firestore..."):
            query = db.collection("questions")
            if search_query:
                query = query.where("chapter", "==", search_query)
            docs = query.limit(5).get()
            
            for doc in docs:
                data = doc.to_dict()
                with st.expander(f"Question ID: `{doc.id}` | Subject: {data.get('subject')} | Mode: {data.get('questionMode')}"):
                    st.markdown(f"**Question Text:**\n{data.get('questionText')}")
                    
                    options = data.get("options", [])
                    correct_idx = data.get("correctOptionIndex", 0)
                    for i, opt in enumerate(options):
                        is_correct = "✅ (Correct)" if i == correct_idx else ""
                        st.markdown(f"- Option {i+1}: {opt} {is_correct}")
                        
                    st.markdown(f"**Explanation:**\n{data.get('explanation', 'N/A')}")
                    if data.get("imageUrl"):
                        st.image(data.get("imageUrl"), caption="Diagram", width=400)
