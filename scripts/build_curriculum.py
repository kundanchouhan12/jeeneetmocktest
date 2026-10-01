"""
build_curriculum.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Authoritative Curriculum Builder for JEE Main 2026 and NEET UG 2026.

Implements the 5-level taxonomy:
exam -> subject -> official_unit -> topic -> question_modes

4 Strict Question Modes:
- TEXT (T)      : Conceptual, theoretical, definitions, statements.
- NUMERICAL (N) : Calculation, formula application, math validation.
- DIAGRAM (D)   : Deterministic vector plots, ray optics, circuits, graphs.
- STRUCTURE (S) : Chemical molecular structures, mechanisms, cell models.

Rules Enforced:
1. No free-form AI image generation (ai_image_generation: False).
2. Deterministic renderers for Maths, Physics, Chemistry.
3. Authentic source or deterministic diagrams for Biology.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import json
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_JSON_PATH = os.path.join(SCRIPT_DIR, "curriculum.json")

def create_topic(topic_name: str, modes: list[str], preferred_mode: str,
                 diagram_source: str = "NONE",
                 ai_generation_allowed: bool = True,
                 difficulty_range: list[str] = None):
    if difficulty_range is None:
        difficulty_range = ["Easy", "Medium", "Hard"]
    return {
        "topic": topic_name,
        "allowed_modes": modes,
        "preferred_mode": preferred_mode,
        "diagram_source": diagram_source,
        "ai_image_generation": False,
        "ai_generation_allowed": ai_generation_allowed,
        "difficulty_range": difficulty_range
    }

# ─── 1. JEE MAIN — MATHEMATICS (14 Units) ──────────────────────────────────
JEE_MATHS_UNITS = [
    {
        "unit_number": 1,
        "unit_name": "Sets, Relations & Functions",
        "ncert_chapters": ["Sets, Relations, And Functions", "Sets", "Relations and Functions"],
        "topics": [
            create_topic("Sets, Subsets, Operations & Venn Diagrams", ["TEXT", "NUMERICAL", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Relations, Types & Equivalence Relations", ["TEXT", "NUMERICAL"], "TEXT"),
            create_topic("Functions, Types (One-One, Onto) & Composition", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 2,
        "unit_name": "Complex Numbers & Quadratic Equations",
        "ncert_chapters": ["Complex Numbers & Quadratic Equations", "Complex Numbers and Quadratic Equations"],
        "topics": [
            create_topic("Complex Plane, Argand Diagram & Geometry", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Modulus, Argument & Algebra of Complex Numbers", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Quadratic Equations, Roots & Nature of Roots", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 3,
        "unit_name": "Matrices & Determinants",
        "ncert_chapters": ["Matrices & Determinants", "Matrices", "Determinants"],
        "topics": [
            create_topic("Matrices, Operations & Types", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Determinants, Adjoint & Inverse", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("System of Linear Equations & Consistency", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Area of Triangles & Collinearity using Determinants", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 4,
        "unit_name": "Permutations & Combinations",
        "ncert_chapters": ["Permutations And Combinations", "Permutations and Combinations"],
        "topics": [
            create_topic("Fundamental Principle of Counting", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Permutations & Combinations Applications", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 5,
        "unit_name": "Binomial Theorem",
        "ncert_chapters": ["Binomial Theorem"],
        "topics": [
            create_topic("Binomial Expansion & General Term", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Middle Term & Greatest Term", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Properties of Binomial Coefficients & Applications", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 6,
        "unit_name": "Sequence & Series",
        "ncert_chapters": ["Sequence & Series", "Sequences and Series"],
        "topics": [
            create_topic("Arithmetic Progression (AP) & Properties", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Geometric Progression (GP) & Properties", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Arithmetic Mean (AM), Geometric Mean (GM) & Relations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 7,
        "unit_name": "Limits, Continuity & Differentiability",
        "ncert_chapters": ["Limit, Continuity & Differentiability", "Continuity and Differentiability"],
        "topics": [
            create_topic("Limits of Algebraic & Trigonometric Functions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Continuity & Discontinuity of Functions", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Derivatives, Chain Rule & Implicit Differentiation", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Monotonicity, Tangents, Normals, Maxima & Minima", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 8,
        "unit_name": "Integral Calculus",
        "ncert_chapters": ["Integral Calculus", "Integrals", "Application of Integrals"],
        "topics": [
            create_topic("Indefinite Integration Methods (Substitution, Parts, Partial Fractions)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Definite Integrals & Properties", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Area Under Curves & Bounded Regions", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 9,
        "unit_name": "Differential Equations",
        "ncert_chapters": ["Differential Equations"],
        "topics": [
            create_topic("Order & Degree of Differential Equations", ["TEXT", "NUMERICAL"], "TEXT"),
            create_topic("Formation & Solution (Variable Separation, Homogeneous, Linear DE)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 10,
        "unit_name": "Coordinate Geometry",
        "ncert_chapters": ["Coordinate Geometry", "Straight Lines", "Conic Sections"],
        "topics": [
            create_topic("Cartesian Coordinates, Distance & Section Formula, Locus", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Straight Lines, Slope, Intercepts & Pair of Lines", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Circle, Standard Forms, Tangents & Normals", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Parabola, Ellipse & Hyperbola Standard Equations & Geometry", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 11,
        "unit_name": "Three Dimensional Geometry",
        "ncert_chapters": ["Three Dimensional Geometry", "3D Geometry"],
        "topics": [
            create_topic("Direction Cosines & Direction Ratios", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Equation of Lines in 3D Space", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Skew Lines, Intersection & Shortest Distance", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 12,
        "unit_name": "Vector Algebra",
        "ncert_chapters": ["Vector Algebra", "Vectors"],
        "topics": [
            create_topic("Vectors, Components, Addition & Scalar Multiplication", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Dot Product (Scalar Product) & Applications", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Cross Product (Vector Product) & Area Applications", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 13,
        "unit_name": "Statistics & Probability",
        "ncert_chapters": ["Statistics", "Probability", "Statistics & Probability"],
        "topics": [
            create_topic("Measures of Dispersion (Mean, Median, Mode, SD, Variance)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Probability, Conditional Probability & Bayes' Theorem", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Probability Distributions & Random Variables", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 14,
        "unit_name": "Trigonometry",
        "ncert_chapters": ["Trigonometry", "Trigonometric Functions", "Inverse Trigonometric Functions"],
        "topics": [
            create_topic("Trigonometric Identities, Functions & Equations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Inverse Trigonometric Functions, Graphs & Properties", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    }
]

# ─── 2. PHYSICS (JEE MAIN & NEET UG — 20 Units) ───────────────────────────
PHYSICS_UNITS = [
    {
        "unit_number": 1,
        "unit_name": "Units & Measurements",
        "ncert_chapters": ["Units, Dimensions And Measurement", "Units and Measurements", "Mathematics In Physics"],
        "topics": [
            create_topic("SI Units, Dimensional Analysis & Applications", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Errors in Measurement, Significant Figures & Least Count", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 2,
        "unit_name": "Kinematics",
        "ncert_chapters": ["Motion In One Dimension", "Motion In Two Dimension", "Motion in a Straight Line", "Motion in a Plane"],
        "topics": [
            create_topic("1D Motion, Position-Time & Velocity-Time Graphs", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Relative Motion in 1D & 2D", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Projectile Motion & Trajectory Curves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Uniform & Non-Uniform Circular Motion", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 3,
        "unit_name": "Laws of Motion",
        "ncert_chapters": ["Newton's Laws Of Motion", "Friction", "Laws of Motion"],
        "topics": [
            create_topic("Newton's Laws of Motion & Momentum", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Free Body Diagrams (FBD) & Equilibrium of Concurrent Forces", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Friction (Static, Kinetic) & Banking of Roads", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 4,
        "unit_name": "Work, Energy & Power",
        "ncert_chapters": ["Work, Energy, Power And Collision", "Work, Energy and Power"],
        "topics": [
            create_topic("Work Done by Constant & Variable Forces, Force-Displacement Graphs", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Kinetic Energy, Work-Energy Theorem & Power", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Potential Energy, Spring Force, Mechanical Energy Conservation", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Elastic & Inelastic Collisions, Vertical Circular Motion", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 5,
        "unit_name": "Rotational Motion",
        "ncert_chapters": ["Rotational Motion", "System of Particles and Rotational Motion"],
        "topics": [
            create_topic("Center of Mass of Discrete & Continuous Systems", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Torque, Angular Momentum & Conservation Laws", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Moment of Inertia (MOI), Parallel & Perpendicular Axes Theorems", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Rolling Motion on Flat & Inclined Surfaces", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 6,
        "unit_name": "Gravitation",
        "ncert_chapters": ["Gravitation"],
        "topics": [
            create_topic("Newton's Law of Gravitation & Acceleration due to Gravity Variations", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Kepler's Laws of Planetary Motion", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Gravitational Potential Energy & Escape Velocity", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Motion of Satellites & Orbital Velocity", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 7,
        "unit_name": "Properties of Solids & Liquids",
        "ncert_chapters": ["Elasticity", "Fluid Mechanics", "Mechanical Properties of Solids", "Mechanical Properties of Fluids", "Thermal Properties of Matter"],
        "topics": [
            create_topic("Elasticity, Stress-Strain Curves & Hooke's Law", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Fluid Pressure, Pascal's Principle & Manometer Setups", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Viscosity, Stokes' Law, Terminal Velocity & Reynolds Number", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Bernoulli's Principle, Continuity Equation & Venturi Meter", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Surface Tension, Capillary Action & Contact Angle", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Heat Transfer: Conduction, Convection & Radiation Laws", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 8,
        "unit_name": "Thermodynamics",
        "ncert_chapters": ["Thermodynamics", "Thermal Physics"],
        "topics": [
            create_topic("Thermal Equilibrium & Zeroth Law of Thermodynamics", ["TEXT"], "TEXT"),
            create_topic("First Law of Thermodynamics, Heat, Work & Internal Energy", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Thermodynamic Processes (Isothermal, Adiabatic, Isobaric, Isochoric)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Indicator Diagrams (P-V Cycles) & Cyclic Processes", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Second Law of Thermodynamics, Heat Engines, Refrigerators & Carnot Efficiency", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 9,
        "unit_name": "Kinetic Theory",
        "ncert_chapters": ["Kinetic Theory Of Gases", "Kinetic Theory"],
        "topics": [
            create_topic("Ideal Gas Equation, Gas Laws & Molecular Nature of Matter", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("RMS Speed, Average & Most Probable Speeds", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Degrees of Freedom, Law of Equipartition of Energy & Specific Heats", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Mean Free Path & Molecular Collisions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 10,
        "unit_name": "Oscillations & Waves",
        "ncert_chapters": ["Simple Harmonic Motion", "Wave Motion", "Oscillations", "Waves"],
        "topics": [
            create_topic("Simple Harmonic Motion (SHM), Equations & Energy in SHM", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Spring-Mass Systems, Simple & Physical Pendulums", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Wave Motion, Transverse & Longitudinal Waves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Superposition Principle, Standing Waves & Organ Pipes", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Beats & Doppler Effect in Sound", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 11,
        "unit_name": "Electrostatics",
        "ncert_chapters": ["Electrostatics", "Electric Charges and Fields", "Electrostatic Potential and Capacitance"],
        "topics": [
            create_topic("Electric Charges, Coulomb's Law & Forces between Multiple Charges", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Electric Field, Field Lines & Electric Dipoles", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Electric Flux, Gauss's Law & Applications (Spheres, Sheets, Wires)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Electric Potential, Potential Energy & Equipotential Surfaces", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Capacitors, Dielectrics, Series/Parallel Combinations & Energy Stored", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 12,
        "unit_name": "Current Electricity",
        "ncert_chapters": ["Current Electricity"],
        "topics": [
            create_topic("Electric Current, Drift Velocity & Ohm's Law", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Resistors, Color Code, Series & Parallel Combinations", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("EMF, Internal Resistance, Cells in Series & Parallel", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Kirchhoff's Laws & Circuit Analysis", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Wheatstone Bridge, Metre Bridge & Potentiometer Circuits", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 13,
        "unit_name": "Magnetic Effects & Magnetism",
        "ncert_chapters": ["Magnetic Effect Of Current", "Moving Charges and Magnetism", "Magnetism and Matter"],
        "topics": [
            create_topic("Biot-Savart Law, Magnetic Field of Straight Wires & Loops", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Ampere's Circuital Law & Solenoid/Toroid Magnetic Fields", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Lorentz Force on Moving Charges & Current Carrying Conductors", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Moving Coil Galvanometer, Ammeter & Voltmeter Conversion", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Bar Magnet, Magnetic Dipole Moment & Earth's Magnetism", ["TEXT", "NUMERICAL", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 14,
        "unit_name": "EMI & AC",
        "ncert_chapters": ["Electromagnetic Induction", "Alternating Current", "Electromagnetic Induction and Alternating Currents"],
        "topics": [
            create_topic("Faraday's Laws of Induction & Lenz's Law", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Motional EMF & Eddy Currents", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Self & Mutual Inductance", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Alternating Currents, Peak & RMS Values, Phasor Diagrams", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("LCR Series Circuit, Resonance, Q-Factor & Power in AC Circuits", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("AC Generator & Transformer Principles & Calculations", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 15,
        "unit_name": "Electromagnetic Waves",
        "ncert_chapters": ["Electromagnetic Waves"],
        "topics": [
            create_topic("Displacement Current & Maxwell's Equations Overview", ["TEXT"], "TEXT"),
            create_topic("Electromagnetic Waves Characteristics & Transverse Nature", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Electromagnetic Spectrum, Wavelength Ranges & Applications", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 16,
        "unit_name": "Optics",
        "ncert_chapters": ["Optics", "Ray Optics and Optical Instruments", "Wave Optics"],
        "topics": [
            create_topic("Reflection at Spherical Mirrors & Mirror Formula", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Refraction at Plane Surfaces, Snell's Law & Total Internal Reflection", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Thin Lenses, Lens Maker's Formula & Combinations", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Refraction through Prism & Dispersion of Light", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Optical Instruments (Microscopes, Telescopes) & Magnification", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Wave Optics: Huygens' Principle & Wavefronts", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Interference of Light & Young's Double Slit Experiment (YDSE)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Diffraction at Single Slit & Polarization of Light", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 17,
        "unit_name": "Dual Nature",
        "ncert_chapters": ["Dual Nature of Radiation and Matter", "Modern Physics"],
        "topics": [
            create_topic("Photoelectric Effect, Stopping Potential & Work Function", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Einstein's Photoelectric Equation & Experimental Graphs", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Matter Waves, de Broglie Wavelength & Davisson-Germer Experiment", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 18,
        "unit_name": "Atoms & Nuclei",
        "ncert_chapters": ["Atoms", "Nuclei", "Modern Physics"],
        "topics": [
            create_topic("Alpha-Particle Scattering & Rutherford's Atomic Model", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Bohr's Model of Hydrogen Atom & Energy Levels Spectrum", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Composition & Size of Nucleus, Mass Defect & Binding Energy Curves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Radioactivity, Nuclear Fission & Nuclear Fusion", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 19,
        "unit_name": "Electronic Devices",
        "ncert_chapters": ["Semiconductor Electronics", "Modern Physics", "Electronic Devices"],
        "topics": [
            create_topic("Intrinsic & Extrinsic Semiconductors, Energy Bands", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("P-N Junction Diode, Forward/Reverse Bias & I-V Characteristic Curves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Diodes as Rectifiers (Half-Wave, Full-Wave)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Special Diodes: Zener Diode, LED, Photodiode, Solar Cell", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Logic Gates (AND, OR, NOT, NAND, NOR, XOR) & Truth Tables", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 20,
        "unit_name": "Experimental Skills",
        "ncert_chapters": ["Practical Physics", "Experimental Skills"],
        "topics": [
            create_topic("Vernier Calipers, Screw Gauge & Spherometer Readings", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Simple Pendulum (Dissipation of Energy / Time Period vs Length Graphs)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Metre Bridge & Potentiometer Practical Setups", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Focal Length of Convex Lens & Concave Mirror (u-v Plots)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Resonance Tube & Speed of Sound Determination", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Diode Characteristic Curve Determination Setups", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
        ]
    }
]

# ─── 3. CHEMISTRY (JEE MAIN & NEET UG — 20 Units) ─────────────────────────
CHEMISTRY_UNITS = [
    # Physical Chemistry (Units 1-8)
    {
        "unit_number": 1,
        "unit_name": "Some Basic Concepts of Chemistry",
        "ncert_chapters": ["Some Basic Concepts Of Chemistry", "Some Basic Concepts of Chemistry"],
        "topics": [
            create_topic("Mole Concept, Molar Mass & Percentage Composition", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Empirical & Molecular Formula Determination", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Stoichiometry, Limiting Reagent & Chemical Reactions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 2,
        "unit_name": "Atomic Structure",
        "ncert_chapters": ["Structure Of Atom", "Structure of Atom"],
        "topics": [
            create_topic("Electromagnetic Radiation, Photoelectric Effect & Planck's Quantum Theory", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Bohr Model of Hydrogen Atom & Line Spectra", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Quantum Numbers, Orbital Shapes (s, p, d) & Radial Probability Curves", ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Aufbau Principle, Pauli Exclusion & Hund's Rule Electronic Configurations", ["TEXT", "NUMERICAL", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 3,
        "unit_name": "Chemical Bonding & Molecular Structure",
        "ncert_chapters": ["Chemical Bonding", "Chemical Bonding and Molecular Structure"],
        "topics": [
            create_topic("Lewis Dot Structures, Ionic & Covalent Bond Formation", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("VSEPR Theory & Molecular Geometries", ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Hybridisation (sp, sp2, sp3, sp3d, sp3d2) & Overlap Schemes", ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Molecular Orbital Theory (MOT), Bond Order & Magnetism (B2 to Ne2)", ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Dipole Moments & Hydrogen Bonding Types", ["TEXT", "NUMERICAL", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 4,
        "unit_name": "Chemical Thermodynamics",
        "ncert_chapters": ["Thermodynamics", "Chemical Thermodynamics"],
        "topics": [
            create_topic("State Functions, Work, Heat, Enthalpy & First Law Calculations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Hess's Law of Constant Heat Summation & Bond Enthalpies", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Entropy (S), Second Law & Spontaneity of Reactions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Gibbs Free Energy (Delta G), Spontaneity & Equilibrium Constant Relation", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 5,
        "unit_name": "Solutions",
        "ncert_chapters": ["Solutions"],
        "topics": [
            create_topic("Concentration Units (Molarity, Molality, Mole Fraction, ppm)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Vapour Pressure of Liquid Solutions, Raoult's Law & Ideal/Non-Ideal Curves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Colligative Properties (Boiling Elevation, Freezing Depression, Osmotic Pressure)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Abnormal Molar Mass & Van 't Hoff Factor (i)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 6,
        "unit_name": "Equilibrium",
        "ncert_chapters": ["Equilibrium"],
        "topics": [
            create_topic("Law of Chemical Equilibrium, Kp, Kc & Reaction Quotient (Q)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Le Chatelier's Principle & Effect of Conditions on Equilibrium", ["TEXT", "NUMERICAL", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Ionic Equilibrium, Arrhenius, Bronsted-Lowry & Lewis Acid-Base Concepts", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Ionisation of Water, pH Scale, Common Ion Effect & Buffer Solutions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Solubility Product (Ksp), Precipitation Conditions & Salt Hydrolysis", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 7,
        "unit_name": "Redox Reactions & Electrochemistry",
        "ncert_chapters": ["Redox Reactions", "Electrochemistry"],
        "topics": [
            create_topic("Oxidation Number Concept & Balancing Redox Reactions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Electrolytic Conductance, Molar Conductivity & Kohlrausch's Law", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Electrochemical Cells, Galvanic Cells & Standard Electrode Potentials", ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Nernst Equation, EMF of Cells & Equilibrium Constant / Delta G Relations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Faraday's Laws of Electrolysis, Batteries & Corrosion", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 8,
        "unit_name": "Chemical Kinetics",
        "ncert_chapters": ["Chemical Kinetics"],
        "topics": [
            create_topic("Rate of Reaction, Rate Law, Order & Molecularity of Reactions", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Integrated Rate Equations & Half-Life (Zero & First Order)", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Arrhenius Equation, Activation Energy & Reaction Profiles", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Collision Theory of Chemical Reactions", ["TEXT"], "TEXT"),
        ]
    },
    # Inorganic Chemistry (Units 9-12)
    {
        "unit_number": 9,
        "unit_name": "Classification of Elements & Periodicity",
        "ncert_chapters": ["Classification Of Elements", "Classification of Elements and Periodicity in Properties"],
        "topics": [
            create_topic("Modern Periodic Law & Layout of the Periodic Table", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Periodic Trends in Atomic & Ionic Radii", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Periodic Trends in Ionisation Enthalpy, Electron Gain Enthalpy & Electronegativity", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Periodic Trends in Chemical Reactivity & Valency / Oxidation States", ["TEXT"], "TEXT"),
        ]
    },
    {
        "unit_number": 10,
        "unit_name": "p-Block Elements",
        "ncert_chapters": ["P-Block Elements", "p-Block Elements"],
        "topics": [
            create_topic("Group 13 & 14 Elements: Electronic Configuration & Oxidation States", ["TEXT", "STRUCTURE"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Group 15, 16, 17 & 18 Elements: Trends in Hydrides, Oxides & Halides", ["TEXT", "STRUCTURE"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Important Inorganic Structures (Diborane, Borax, Silicates, Oxoacids of P, S, Cl, Xenon Fluorides)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 11,
        "unit_name": "d- and f-Block Elements",
        "ncert_chapters": ["The d- and f-Block Elements", "d and f Block Elements"],
        "topics": [
            create_topic("Transition Elements (3d series) Characteristics: Variable Oxidation States, Colour, Catalytic Properties", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Magnetic Properties & Spin-Only Formula Calculations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Preparation, Properties & Structures of KMnO4 & K2Cr2O7", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Lanthanoids & Actinoids: Electronic Configurations, Lanthanoid Contraction", ["TEXT"], "TEXT"),
        ]
    },
    {
        "unit_number": 12,
        "unit_name": "Coordination Compounds",
        "ncert_chapters": ["Coordination Compounds"],
        "topics": [
            create_topic("Werner's Theory, Ligands, Coordination Number & IUPAC Nomenclature", ["TEXT", "STRUCTURE"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Isomerism in Coordination Compounds (Structural & Stereoisomerism)", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Valence Bond Theory (VBT) & Inner/Outer Orbital Complexes", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Crystal Field Theory (CFT), Crystal Field Splitting (Octahedral & Tetrahedral)", ["TEXT", "NUMERICAL", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Colour, Magnetic Moment & Stability of Coordination Complexes", ["TEXT", "NUMERICAL", "STRUCTURE"], "NUMERICAL", diagram_source="DETERMINISTIC"),
        ]
    },
    # Organic Chemistry (Units 13-20)
    {
        "unit_number": 13,
        "unit_name": "Purification & Characterisation of Organic Compounds",
        "ncert_chapters": ["Purification and Characterisation of Organic Compounds"],
        "topics": [
            create_topic("Purification Techniques: Crystallisation, Fractional Distillation, Steam Distillation", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Chromatography: Column & Thin Layer Chromatography (TLC)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="DETERMINISTIC"),
            create_topic("Qualitative Elemental Analysis (Lassaigne's Test for N, S, Halogens)", ["TEXT"], "TEXT"),
            create_topic("Quantitative Estimation Calculations (Dumas, Kjeldahl, Carius Methods)", ["TEXT", "NUMERICAL"], "NUMERICAL"),
        ]
    },
    {
        "unit_number": 14,
        "unit_name": "Basic Principles of Organic Chemistry",
        "ncert_chapters": ["Organic Chemistry Basics", "General Organic Chemistry", "Organic Chemistry - Some Basic Principles and Techniques"],
        "topics": [
            create_topic("IUPAC Nomenclature of Simple & Polyfunctional Organic Compounds", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Hybridisation, Shapes of Molecules & Bond Cleavage (Homolytic & Heterolytic)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Electronic Displacements: Inductive, Electromeric, Resonance & Hyperconjugation", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Carbocations, Carbanions & Free Radicals Stability", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Isomerism: Structural Isomerism & Stereoisomerism (Geometrical & Optical)", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 15,
        "unit_name": "Hydrocarbons",
        "ncert_chapters": ["Hydrocarbons"],
        "topics": [
            create_topic("Alkanes: Conformations (Ethane, Butane - Newman & Sawhorse)", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Alkanes: Free Radical Halogenation Mechanism", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Alkenes: Geometrical Isomerism (cis/trans & E/Z)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Alkenes: Electrophilic Addition (Markovnikov & Anti-Markovnikov Rule)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Alkenes: Ozonolysis & Polymerization Reactions", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Alkynes: Acidity of Terminal Alkynes & Addition Reactions", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Aromatic Hydrocarbons: Huckel's Rule of Aromaticity", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Electrophilic Aromatic Substitution (Nitration, Halogenation, Friedel-Crafts)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 16,
        "unit_name": "Organic Compounds Containing Halogens",
        "ncert_chapters": ["Haloalkanes and Haloarenes", "Haloalkanes"],
        "topics": [
            create_topic("Alkyl Halides: Classification & Nature of C-X Bond", ["TEXT", "STRUCTURE"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Nucleophilic Substitution Mechanisms (SN1 vs SN2 Kinetics & Stereochemistry)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Elimination Reactions (E1 vs E2, Saytzeff's Rule)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Aryl Halides: Low Reactivity towards Nucleophilic Substitution & Directing Effects", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 17,
        "unit_name": "Organic Compounds Containing Oxygen",
        "ncert_chapters": ["Alcohols, Phenols and Ethers", "Aldehydes, Ketones And Carboxylic Acids", "Aldehydes, Ketones and Carboxylic Acids"],
        "topics": [
            create_topic("Alcohols: Preparation, Hydrogen Bonding & Lucas / Victor Meyer Tests", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Phenols: Acidity, Kolbe's Reaction & Reimer-Tiemann Reaction Mechanisms", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Ethers: Preparation (Williamson's Synthesis) & Cleavage by HI", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Aldehydes & Ketones: Nucleophilic Addition Reactions (HCN, NaHSO3, Grignard)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Named Reactions: Aldol Condensation, Cannizzaro, Clemmensen, Wolff-Kishner", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Distinguishing Tests: Tollens' Test, Fehling's Test & Iodoform Reaction", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Carboxylic Acids: Acidity Trends & Hell-Volhard-Zelinsky (HVZ) Reaction", ["TEXT", "NUMERICAL", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 18,
        "unit_name": "Organic Compounds Containing Nitrogen",
        "ncert_chapters": ["Amines", "Organic Compounds Containing Nitrogen"],
        "topics": [
            create_topic("Amines: Basicity Trends (Aliphatic vs Aromatic in Gaseous & Aqueous Phases)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Preparation: Gabriel Phthalimide Synthesis & Hoffmann Bromamide Degradation", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Chemical Reactions: Carbylamine Reaction & Hinsberg's Reagent Test", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Diazonium Salts: Diazotisation, Sandmeyer, Gattermann & Coupling Reactions", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 19,
        "unit_name": "Biomolecules",
        "ncert_chapters": ["Biomolecules"],
        "topics": [
            create_topic("Carbohydrates: Monosaccharides (Glucose, Fructose - Open Chain & Haworth Ring Structures)", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Disaccharides & Polysaccharides (Glycosidic Linkages in Sucrose, Maltose, Starch, Cellulose)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Proteins: Amino Acids, Zwitterion Form, Peptide Bonds & Protein Structures (Primary to Quaternary)", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Denaturation of Proteins & Enzyme Action", ["TEXT"], "TEXT"),
            create_topic("Nucleic Acids: Nucleotides, DNA Double Helix & RNA Types", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Vitamins & Hormones Classification & Functions", ["TEXT"], "TEXT"),
        ]
    },
    {
        "unit_number": 20,
        "unit_name": "Practical Chemistry",
        "ncert_chapters": ["Practical Chemistry"],
        "topics": [
            create_topic("Identification of Functional Groups (Unsaturation, Alcoholic, Phenolic, Aldehydic, Ketonic, Carboxylic, Amino)", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Titration Experiments: Acid-Base & Redox Titrations (KMnO4, Oxalic Acid, Mohr's Salt Calculations)", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="DETERMINISTIC"),
            create_topic("Qualitative Salt Analysis: Detection of Cations (Pb2+, Cu2+, Al3+, Fe3+, Zn2+, Ni2+, Ca2+, Ba2+, Mg2+, NH4+)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="DETERMINISTIC"),
            create_topic("Qualitative Salt Analysis: Detection of Anions (CO3^2-, S^2-, SO4^2-, NO3^-, Cl^-, Br^-, I^-)", ["TEXT"], "TEXT"),
            create_topic("Preparation of Inorganic & Organic Compounds (Alum, Acetanilide, Iodoform)", ["TEXT", "NUMERICAL", "STRUCTURE"], "TEXT", diagram_source="DETERMINISTIC"),
        ]
    }
]

# ─── 4. NEET UG — BIOLOGY (10 Broad Units) ─────────────────────────────────
NEET_BIOLOGY_UNITS = [
    {
        "unit_number": 1,
        "unit_name": "Diversity in Living World",
        "ncert_chapters": ["Living World", "Biological Classification", "Plant Kingdom", "Animal Kingdom"],
        "topics": [
            create_topic("What is Living & Characteristics of Living Organisms", ["TEXT"], "TEXT"),
            create_topic("Biodiversity, Need for Classification & Systematics", ["TEXT"], "TEXT"),
            create_topic("Taxonomy, Taxonomic Hierarchy & Taxa", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Species Concept & Binomial Nomenclature Rules", ["TEXT"], "TEXT"),
            create_topic("Five Kingdom Classification (Whittaker's Scheme)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Kingdom Monera: Archaebacteria, Eubacteria & Mycoplasma", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Kingdom Protista: Chrysophytes, Dinoflagellates, Euglenoids, Slime Moulds, Protozoans", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Kingdom Fungi: Phycomycetes, Ascomycetes, Basidiomycetes, Deuteromycetes", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Lichens, Viruses, Viroids & Prions Structure", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Kingdom: Algae (Chlorophyceae, Phaeophyceae, Rhodophyceae)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Kingdom: Bryophytes (Liverworts & Mosses Life Cycles)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Kingdom: Pteridophytes & Gymnosperms Life Cycles & Anatomy", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Animal Kingdom: Non-Chordates (Porifera to Echinodermata & Hemichordata)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Animal Kingdom: Chordates (Protochordata, Cyclostomata, Chondrichthyes, Osteichthyes, Amphibia, Reptilia, Aves, Mammalia)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 2,
        "unit_name": "Structural Organisation in Animals & Plants",
        "ncert_chapters": ["Morphology Of Flowering Plants", "Anatomy Of Flowering Plants", "Structural Organisation In Animals"],
        "topics": [
            create_topic("Plant Morphology: Root Types & Modifications", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Morphology: Stem Modifications & Functions", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Morphology: Leaf Types, Venation & Modifications", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Inflorescence Types (Racemose & Cymose)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Flower Structure, Aestivation & Placentation Types", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Fruit Types & Seed Structure (Monocot & Dicot Seeds)", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Tissues: Meristematic & Permanent Tissues (Simple & Complex)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Internal Anatomy of Dicot & Monocot Root, Stem & Leaf", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Families: Solanaceae, Fabaceae & Liliaceae Floral Formula & Diagrams", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Animal Tissues: Epithelial, Connective, Muscular & Neural Tissues", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Morphology & Anatomy of Cockroach (Digestive, Circulatory, Nervous, Reproductive)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Morphology & Anatomy of Frog (Digestive, Respiratory, Circulatory, Nervous, Reproductive)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 3,
        "unit_name": "Cell Structure & Function",
        "ncert_chapters": ["Cell Biology", "Cell - The Unit of Life", "Biomolecules", "Cell Cycle and Cell Division"],
        "topics": [
            create_topic("Cell Theory & Prokaryotic vs Eukaryotic Cell Structure", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Plant Cell vs Animal Cell Ultrastructure", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Cell Membrane (Fluid Mosaic Model) & Cell Wall", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Endomembrane System: Endoplasmic Reticulum, Golgi Apparatus, Lysosomes & Vacuoles", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Mitochondria & Plastids Structure & Semiautonomous Nature", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Ribosomes, Cytoskeleton, Cilia, Flagella & Centrioles", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Nucleus, Chromatin & Chromosome Structure (Centromere Types)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biomolecules: Amino Acids, Lipids, Carbohydrates & Nucleotides", ["TEXT", "STRUCTURE"], "STRUCTURE", diagram_source="DETERMINISTIC"),
            create_topic("Enzymes: Properties, Factors Affecting Activity & Allosteric Inhibition", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Cell Cycle: Interphase (G1, S, G2) & Regulation", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Mitosis Stages & Significance", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Meiosis I & Meiosis II Stages, Crossing Over & Synaptonemal Complex", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 4,
        "unit_name": "Plant Physiology",
        "ncert_chapters": ["Plant Physiology", "Photosynthesis in Higher Plants", "Respiration in Plants", "Plant Growth and Development"],
        "topics": [
            create_topic("Photosynthesis Overview & Chloroplast Pigment Systems (PS I & PS II)", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Light Reaction, Z-Scheme & Photophosphorylation (Cyclic & Non-Cyclic)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Calvin Cycle (C3 Pathway), C4 Pathway & CAM Mechanism", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Photorespiration (C2 Cycle) & Factors Affecting Photosynthesis", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Respiration in Plants: Glycolysis (EMP Pathway)", ["TEXT", "DIAGRAM", "NUMERICAL"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Krebs Cycle (TCA Cycle) & Electron Transport System (ETS/Oxidative Phosphorylation)", ["TEXT", "DIAGRAM", "NUMERICAL"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Fermentation, Respiratory Quotient (RQ) & Energy Output Calculations", ["TEXT", "NUMERICAL"], "NUMERICAL"),
            create_topic("Plant Growth Regulators: Auxins, Gibberellins, Cytokinins, Ethylene & Abscisic Acid", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Nitrogen Cycle & Biological Nitrogen Fixation (Nitrogenase Enzyme)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 5,
        "unit_name": "Human Physiology",
        "ncert_chapters": ["Human Physiology", "Breathing and Exchange of Gases", "Body Fluids and Circulation", "Excretory Products and their Elimination", "Locomotion and Movement", "Neural Control and Coordination", "Chemical Coordination and Integration"],
        "topics": [
            create_topic("Breathing Mechanism, Respiratory System Anatomy & Respiratory Volumes/Capacities", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Gas Exchange at Alveolar & Tissue Levels, Oxygen Dissociation Curves", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Blood Composition, ABO & Rh Blood Groups, Blood Clotting Cascade", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Human Heart Anatomy, Conducting System & Cardiac Cycle", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Electrocardiogram (ECG Waves: P, QRS, T), Double Circulation & Blood Pressure", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Human Excretory System & Internal Anatomy of Kidney", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Nephron Structure, Urine Formation & Counter-Current Mechanism", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Regulation of Kidney Function (RAAS, ADH, ANF) & Micturition", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Human Skeletal System (Axial & Appendicular Skeleton)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Types of Joints & Sarcomere Structure in Muscle Contraction", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Mechanism of Muscle Contraction (Sliding Filament Theory)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Human Nervous System & Neuron Ultrastructure", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Conduction of Nerve Impulse (Depolarisation/Repolarisation) & Synapse", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Reflex Arc & Brain Anatomy (Forebrain, Midbrain, Hindbrain)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Endocrine Glands (Pituitary, Thyroid, Parathyroid, Adrenal, Pancreas, Gonads)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Hormones Action Mechanism (Peptide vs Steroid Hormones) & Feedback Loops", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 6,
        "unit_name": "Reproduction",
        "ncert_chapters": ["Reproduction", "Sexual Reproduction in Flowering Plants", "Human Reproduction", "Reproductive Health"],
        "topics": [
            create_topic("Flower Anatomy & Microsporogenesis (Pollen Grain Development)", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Megasporogenesis & Embryo Sac Development (Monosporic 7-Celled 8-Nucleate)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Pollination Agents & Outbreeding Devices", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Pollen-Pistil Interaction & Double Fertilisation", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Endosperm Development, Dicot & Monocot Embryogeny", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Apomixis & Polyembryony", ["TEXT"], "TEXT"),
            create_topic("Male Reproductive System Anatomy & Testis Histology", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Female Reproductive System Anatomy & Ovary Histology", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Gametogenesis: Spermatogenesis & Oogenesis Differences", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Menstrual Cycle: Hormonal Control & Ovarian / Uterine Phases", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Fertilisation, Blastocyst Formation & Implantation", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Pregnancy, Placenta Functions, Parturition & Lactation", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Reproductive Health: Population Explosion & Contraceptive Methods", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Medical Termination of Pregnancy (MTP) & Sexually Transmitted Infections (STIs)", ["TEXT"], "TEXT"),
            create_topic("Infertility & Assisted Reproductive Technologies (IVF, ZIFT, GIFT, ICSI, IUI)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 7,
        "unit_name": "Genetics & Evolution",
        "ncert_chapters": ["Genetics", "Evolution", "Principles of Inheritance and Variation", "Molecular Basis of Inheritance"],
        "topics": [
            create_topic("Mendel's Laws of Inheritance (Dominance & Segregation)", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Monohybrid Cross Ratios (Phenotypic & Genotypic)", ["NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Dihybrid Cross & Law of Independent Assortment", ["NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Incomplete Dominance, Codominance & Multiple Alleles (ABO Blood Group)", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Pedigree Analysis Symbols & Inheritance Pattern Determination", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Chromosomal Theory of Inheritance (Sutton & Boveri)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Morgan's Experiments on Drosophila: Linkage & Recombination", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Sex Determination Mechanisms (XX-XY, XX-XO, ZZ-ZW, Honeybee)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Genetic Disorders: Mendelian (Hemophilia, Sickle Cell, Thalassemia) & Chromosomal (Down, Turner, Klinefelter)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("DNA as Genetic Material (Griffith, Avery-MacLeod-McCarty, Hershey-Chase)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Structure of DNA (Watson-Crick Double Helix) & RNA Types", ["TEXT", "STRUCTURE", "DIAGRAM"], "STRUCTURE", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("DNA Packaging & Nucleosome Structure", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("DNA Replication: Semiconservative Mechanism & Meselson-Stahl Experiment", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Transcription Unit, Promoters & Post-Transcriptional Processing", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Genetic Code Properties & Wobble Hypothesis", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Translation & Protein Synthesis Mechanism", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Regulation of Gene Expression: Lac Operon Model", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Human Genome Project (HGP) & DNA Fingerprinting Steps", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Origin of Life Theories & Miller-Urey Experiment", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Evidence of Evolution: Homologous, Analogous Organs & Embryological Evidence", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Darwin's Theory of Natural Selection & Modern Synthetic Theory", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Hardy-Weinberg Principle & Algebraic Frequency Calculations", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Adaptive Radiation & Human Evolution Stages", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 8,
        "unit_name": "Biology & Human Welfare",
        "ncert_chapters": ["Microbes In Human Welfare", "Human Health and Disease", "Microbes in Human Welfare"],
        "topics": [
            create_topic("Common Human Diseases caused by Bacteria, Viruses, Protozoa & Fungi", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Life Cycle of Plasmodium (Malaria Parasite)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Immunity: Innate & Acquired Immunity (Humoral vs Cell-Mediated)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Antibody Structure (IgG, IgA, IgM, IgE, IgD) & Antigen-Antibody Binding", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Vaccines, Immunisation & Allergy Mechanism", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("AIDS Pathogenesis, HIV Replication Cycle & Transmission", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Cancer Biology, Types of Tumours & Carcinogens", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Drugs & Alcohol Abuse Effects", ["TEXT"], "TEXT"),
            create_topic("Microbes in Household Food Products (Lactic Acid Bacteria, Yeast)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Industrial Microbes: Fermentation, Antibiotics, Organic Acids & Enzymes", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Microbes in Sewage Treatment (Primary & Secondary Treatment, BOD)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biogas Plant Structure & Methanogenic Bacteria", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biofertilizers & Biocontrol Agents (Bacillus thuringiensis, Trichoderma, Baculoviruses)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    },
    {
        "unit_number": 9,
        "unit_name": "Biotechnology",
        "ncert_chapters": ["Biotechnology", "Biotechnology - Principles and Processes", "Biotechnology and its Applications"],
        "topics": [
            create_topic("Principles of Biotechnology & Recombinant DNA (rDNA) Technology", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Tools of rDNA Technology: Restriction Enzymes (Endonucleases) & Recognition Sequences", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Cloning Vectors (pBR322 Structure, Selectable Markers, ampR, tetR)", ["TEXT", "DIAGRAM", "STRUCTURE"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Competent Host Cells & Methods of Gene Transfer (Microinjection, Biolistics)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Polymerase Chain Reaction (PCR: Denaturation, Annealing, Extension)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Gel Electrophoresis Principle & Ethidium Bromide Staining", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Bioreactors (Simple Stirred-Tank & Sparged) & Downstream Processing", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biotechnological Applications in Agriculture: Bt Cotton & RNA Interference (RNAi)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Applications in Medicine: Genetically Engineered Insulin Production", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Gene Therapy (ADA Deficiency) & Molecular Diagnosis (ELISA, PCR)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Transgenic Animals & Uses in Vaccine Safety Testing", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Bioethics, Biopiracy & Patent Regulations", ["TEXT"], "TEXT"),
        ]
    },
    {
        "unit_number": 10,
        "unit_name": "Ecology & Environment",
        "ncert_chapters": ["Ecology", "Organisms and Populations", "Ecosystem", "Biodiversity and Conservation"],
        "topics": [
            create_topic("Organisms & Environment: Abiotic Factors (Temperature, Water, Light, Soil)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Adaptations of Organisms to Desert, Aquatic & Cold Environments", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Population Attributes & Age Pyramids", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Population Growth Models (Exponential & Logistic Growth Equations)", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Population Interactions: Mutualism, Competition, Predation, Parasitism, Commensalism", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Ecosystem Structure & Productivity (GPP, NPP Calculations)", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Decomposition Process Steps (Fragmentation, Leaching, Catabolism, Humification, Mineralisation)", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Energy Flow & 10% Trophic Law in Ecosystems", ["TEXT", "NUMERICAL", "DIAGRAM"], "NUMERICAL", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Ecological Pyramids (Number, Biomass, Energy - Upright vs Inverted)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Ecological Succession (Hydrarch & Xerarch Succession Stages)", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Nutrient Cycles: Carbon & Phosphorus Cycles", ["TEXT", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biodiversity Patterns: Latitudinal Gradients & Species-Area Relationship Curves", ["TEXT", "NUMERICAL", "DIAGRAM"], "DIAGRAM", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Loss of Biodiversity ('The Evil Quartet') & Causes", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
            create_topic("Biodiversity Conservation: In-situ vs Ex-situ Strategies", ["TEXT", "DIAGRAM"], "TEXT", diagram_source="SOURCE_OR_DETERMINISTIC"),
        ]
    }
]

def build_curriculum():
    data = {
        "metadata": {
            "version": "2026.1",
            "name": "JEE Main & NEET UG Official Curriculum Specification",
            "description": "5-Level Taxonomy: exam -> subject -> official_unit -> topic -> question_modes",
            "modes_legend": {
                "TEXT": "Text, theory, definitions, conceptual understanding",
                "NUMERICAL": "Calculations, formula evaluation, dual-pass math verification",
                "DIAGRAM": "Deterministic vector plots, ray optics, circuit diagrams, apparatus, waveforms",
                "STRUCTURE": "Chemical structures, reactions, molecular geometry, biological models"
            },
            "rules": [
                "Strictly NO decorative or free-form generative AI images (ai_image_generation: false).",
                "Deterministic renderers for STEM diagrams and chemical structures.",
                "Authentic PYQ or deterministic plots for NEET biology visual content.",
                "AI generation must strictly stay within approved unit topics and question modes."
            ]
        },
        "curriculum": {
            "JEE": {
                "Maths": {
                    "official_units_count": len(JEE_MATHS_UNITS),
                    "units": JEE_MATHS_UNITS
                },
                "Physics": {
                    "official_units_count": len(PHYSICS_UNITS),
                    "units": PHYSICS_UNITS
                },
                "Chemistry": {
                    "official_units_count": len(CHEMISTRY_UNITS),
                    "units": CHEMISTRY_UNITS
                }
            },
            "NEET": {
                "Physics": {
                    "official_units_count": len(PHYSICS_UNITS),
                    "units": PHYSICS_UNITS
                },
                "Chemistry": {
                    "official_units_count": len(CHEMISTRY_UNITS),
                    "units": CHEMISTRY_UNITS
                },
                "Biology": {
                    "official_units_count": len(NEET_BIOLOGY_UNITS),
                    "units": NEET_BIOLOGY_UNITS
                }
            }
        },
        "power100_config": {
            "JEE": {
                "total_questions": 100,
                "subject_distribution": {
                    "Physics": 31,
                    "Chemistry": 36,
                    "Maths": 33
                },
                "max_per_unit": 4,
                "difficulty_weights": {
                    "Easy": 3,
                    "Medium": 3,
                    "Hard": 1
                },
                "allowed_modes": ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"]
            },
            "NEET": {
                "total_questions": 100,
                "subject_distribution": {
                    "Physics": 25,
                    "Chemistry": 25,
                    "Biology": 50
                },
                "max_per_unit": 4,
                "difficulty_weights": {
                    "Easy": 3,
                    "Medium": 3,
                    "Hard": 1
                },
                "allowed_modes": ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"]
            }
        }
    }

    with open(OUTPUT_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"[OK] Successfully built curriculum specification at: {OUTPUT_JSON_PATH}")
    print(f"   JEE Maths: {len(JEE_MATHS_UNITS)} units")
    print(f"   JEE/NEET Physics: {len(PHYSICS_UNITS)} units")
    print(f"   JEE/NEET Chemistry: {len(CHEMISTRY_UNITS)} units")
    print(f"   NEET Biology: {len(NEET_BIOLOGY_UNITS)} broad units")

if __name__ == "__main__":
    build_curriculum()
