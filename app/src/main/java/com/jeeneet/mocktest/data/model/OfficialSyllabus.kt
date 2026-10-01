package com.jeeneet.mocktest.data.model

/**
 * OfficialSyllabus.kt
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * Single Source of Truth for JEE Main 2026 and NEET UG 2026 Syllabus in the Android App.
 *
 * Strictly adheres to NTA / NMC Rationalized 2026 Syllabus Specifications:
 * - JEE Maths: 14 Official Units (Excludes deleted Mathematical Reasoning & Induction)
 * - JEE/NEET Physics: 20 Official Units (Excludes deleted Communication Systems)
 * - JEE/NEET Chemistry: 20 Official Units (Excludes deleted States of Matter, Solid State,
 *   Surface Chemistry, Hydrogen, s-Block, Metallurgy, Polymers, Everyday Life, Environmental Chem)
 * - NEET Biology: 10 Official Units (Excludes deleted Digestion & Absorption, Transport in Plants,
 *   Mineral Nutrition, Reproduction in Organisms, Strategies in Food, Environmental Issues)
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
object OfficialSyllabus {

    // ─── JEE MAIN 2026 UNITS ────────────────────────────────────────────────

    val JEE_MATHS_UNITS = listOf(
        "Sets, Relations & Functions",
        "Complex Numbers & Quadratic Equations",
        "Matrices & Determinants",
        "Permutations & Combinations",
        "Binomial Theorem",
        "Sequence & Series",
        "Limits, Continuity & Differentiability",
        "Integral Calculus",
        "Differential Equations",
        "Coordinate Geometry",
        "Three Dimensional Geometry",
        "Vector Algebra",
        "Statistics & Probability",
        "Trigonometry"
    )

    val PHYSICS_UNITS = listOf(
        "Units & Measurements",
        "Kinematics",
        "Laws of Motion",
        "Work, Energy & Power",
        "Rotational Motion",
        "Gravitation",
        "Properties of Solids & Liquids",
        "Thermodynamics",
        "Kinetic Theory of Gases",
        "Oscillations & Waves",
        "Electrostatics",
        "Current Electricity",
        "Magnetic Effects of Current & Magnetism",
        "Electromagnetic Induction & Alternating Currents",
        "Electromagnetic Waves",
        "Optics",
        "Dual Nature of Matter & Radiation",
        "Atoms & Nuclei",
        "Electronic Devices",
        "Experimental Skills"
    )

    val CHEMISTRY_UNITS = listOf(
        "Some Basic Concepts of Chemistry",
        "Atomic Structure",
        "Chemical Bonding & Molecular Structure",
        "Chemical Thermodynamics",
        "Solutions",
        "Equilibrium",
        "Redox Reactions & Electrochemistry",
        "Chemical Kinetics",
        "Classification of Elements & Periodicity",
        "p-Block Elements",
        "d- and f-Block Elements",
        "Coordination Compounds",
        "Purification & Characterisation of Organic Compounds",
        "Basic Principles of Organic Chemistry",
        "Hydrocarbons",
        "Organic Compounds Containing Halogens",
        "Organic Compounds Containing Oxygen",
        "Organic Compounds Containing Nitrogen",
        "Biomolecules",
        "Principles of Practical Chemistry"
    )

    // ─── NEET UG 2026 UNITS ─────────────────────────────────────────────────

    val NEET_BIOLOGY_UNITS = listOf(
        "Diversity in Living World",
        "Structural Organisation in Animals & Plants",
        "Cell Structure & Function",
        "Plant Physiology",
        "Human Physiology",
        "Reproduction",
        "Genetics & Evolution",
        "Biology & Human Welfare",
        "Biotechnology",
        "Ecology & Environment"
    )

    /**
     * Map of official unit name -> all historical NCERT aliases & sub-chapter names.
     * Ensures Room queries seamlessly fetch questions tagged with sub-chapters or legacy aliases.
     */
    private val UNIT_ALIASES: Map<String, List<String>> = mapOf(
        // Physics
        "Units & Measurements" to listOf("Units & Measurements", "Units, Dimensions And Measurement", "Units and Measurements", "Mathematics In Physics"),
        "Kinematics" to listOf("Kinematics", "Motion In One Dimension", "Motion In Two Dimension", "Motion in a Straight Line", "Motion in a Plane"),
        "Laws of Motion" to listOf("Laws of Motion", "Newton's Laws Of Motion", "Friction"),
        "Work, Energy & Power" to listOf("Work, Energy & Power", "Work, Energy, Power And Collision", "Work, Energy and Power"),
        "Rotational Motion" to listOf("Rotational Motion", "System of Particles and Rotational Motion"),
        "Gravitation" to listOf("Gravitation"),
        "Properties of Solids & Liquids" to listOf("Properties of Solids & Liquids", "Elasticity", "Fluid Mechanics", "Mechanical Properties of Solids", "Mechanical Properties of Fluids", "Thermal Properties of Matter"),
        "Thermodynamics" to listOf("Thermodynamics", "Thermal Physics"),
        "Kinetic Theory of Gases" to listOf("Kinetic Theory of Gases", "Kinetic Theory Of Gases", "Kinetic Theory"),
        "Oscillations & Waves" to listOf("Oscillations & Waves", "Simple Harmonic Motion", "Wave Motion", "Oscillations", "Waves"),
        "Electrostatics" to listOf("Electrostatics", "Electric Charges and Fields", "Electrostatic Potential and Capacitance"),
        "Current Electricity" to listOf("Current Electricity"),
        "Magnetic Effects of Current & Magnetism" to listOf("Magnetic Effects of Current & Magnetism", "Magnetic Effect Of Current", "Moving Charges and Magnetism", "Magnetism and Matter"),
        "Electromagnetic Induction & Alternating Currents" to listOf("Electromagnetic Induction & Alternating Currents", "Electromagnetic Induction", "Alternating Current", "EMI & AC"),
        "Electromagnetic Waves" to listOf("Electromagnetic Waves"),
        "Optics" to listOf("Optics", "Ray Optics and Optical Instruments", "Wave Optics"),
        "Dual Nature of Matter & Radiation" to listOf("Dual Nature of Matter & Radiation", "Dual Nature of Radiation and Matter", "Dual Nature", "Modern Physics"),
        "Atoms & Nuclei" to listOf("Atoms & Nuclei", "Atoms", "Nuclei", "Modern Physics"),
        "Electronic Devices" to listOf("Electronic Devices", "Semiconductor Electronics", "Modern Physics"),
        "Experimental Skills" to listOf("Experimental Skills", "Practical Physics"),

        // Chemistry
        "Some Basic Concepts of Chemistry" to listOf("Some Basic Concepts of Chemistry", "Some Basic Concepts Of Chemistry"),
        "Atomic Structure" to listOf("Atomic Structure", "Structure Of Atom", "Structure of Atom"),
        "Chemical Bonding & Molecular Structure" to listOf("Chemical Bonding & Molecular Structure", "Chemical Bonding", "Chemical Bonding and Molecular Structure"),
        "Chemical Thermodynamics" to listOf("Chemical Thermodynamics", "Thermodynamics"),
        "Solutions" to listOf("Solutions"),
        "Equilibrium" to listOf("Equilibrium"),
        "Redox Reactions & Electrochemistry" to listOf("Redox Reactions & Electrochemistry", "Redox Reactions", "Electrochemistry"),
        "Chemical Kinetics" to listOf("Chemical Kinetics"),
        "Classification of Elements & Periodicity" to listOf("Classification of Elements & Periodicity", "Classification Of Elements", "Classification of Elements and Periodicity in Properties"),
        "p-Block Elements" to listOf("p-Block Elements", "P-Block Elements"),
        "d- and f-Block Elements" to listOf("d- and f-Block Elements", "The d- and f-Block Elements", "d and f Block Elements"),
        "Coordination Compounds" to listOf("Coordination Compounds"),
        "Purification & Characterisation of Organic Compounds" to listOf("Purification & Characterisation of Organic Compounds", "Purification and Characterisation of Organic Compounds"),
        "Basic Principles of Organic Chemistry" to listOf("Basic Principles of Organic Chemistry", "Organic Chemistry Basics", "General Organic Chemistry", "Organic Chemistry - Some Basic Principles and Techniques"),
        "Hydrocarbons" to listOf("Hydrocarbons"),
        "Organic Compounds Containing Halogens" to listOf("Organic Compounds Containing Halogens", "Haloalkanes and Haloarenes", "Haloalkanes"),
        "Organic Compounds Containing Oxygen" to listOf("Organic Compounds Containing Oxygen", "Alcohols, Phenols and Ethers", "Aldehydes, Ketones And Carboxylic Acids", "Aldehydes, Ketones and Carboxylic Acids"),
        "Organic Compounds Containing Nitrogen" to listOf("Organic Compounds Containing Nitrogen", "Amines"),
        "Biomolecules" to listOf("Biomolecules"),
        "Principles of Practical Chemistry" to listOf("Principles of Practical Chemistry", "Practical Chemistry"),

        // Maths
        "Sets, Relations & Functions" to listOf("Sets, Relations & Functions", "Sets, Relations, And Functions", "Sets", "Relations and Functions"),
        "Complex Numbers & Quadratic Equations" to listOf("Complex Numbers & Quadratic Equations", "Complex Numbers and Quadratic Equations"),
        "Matrices & Determinants" to listOf("Matrices & Determinants", "Matrices", "Determinants"),
        "Permutations & Combinations" to listOf("Permutations & Combinations", "Permutations And Combinations", "Permutations and Combinations"),
        "Binomial Theorem" to listOf("Binomial Theorem"),
        "Sequence & Series" to listOf("Sequence & Series", "Sequences and Series"),
        "Limits, Continuity & Differentiability" to listOf("Limits, Continuity & Differentiability", "Limit, Continuity & Differentiability", "Continuity and Differentiability"),
        "Integral Calculus" to listOf("Integral Calculus", "Integrals", "Application of Integrals"),
        "Differential Equations" to listOf("Differential Equations"),
        "Coordinate Geometry" to listOf("Coordinate Geometry", "Straight Lines", "Conic Sections", "Circles"),
        "Three Dimensional Geometry" to listOf("Three Dimensional Geometry", "3D Geometry"),
        "Vector Algebra" to listOf("Vector Algebra", "Vectors"),
        "Statistics & Probability" to listOf("Statistics & Probability", "Statistics", "Probability"),
        "Trigonometry" to listOf("Trigonometry", "Trigonometric Functions", "Inverse Trigonometric Functions"),

        // Biology
        "Diversity in Living World" to listOf("Diversity in Living World", "Living World", "Biological Classification", "Plant Kingdom", "Animal Kingdom"),
        "Structural Organisation in Animals & Plants" to listOf("Structural Organisation in Animals & Plants", "Morphology Of Flowering Plants", "Anatomy Of Flowering Plants", "Structural Organisation In Animals", "Morphology of Flowering Plants", "Anatomy of Flowering Plants"),
        "Cell Structure & Function" to listOf("Cell Structure & Function", "Cell Biology", "Cell - The Unit of Life", "Biomolecules", "Cell Cycle and Cell Division"),
        "Plant Physiology" to listOf("Plant Physiology", "Photosynthesis in Higher Plants", "Respiration in Plants", "Plant Growth and Development"),
        "Human Physiology" to listOf("Human Physiology", "Breathing and Exchange of Gases", "Body Fluids and Circulation", "Excretory Products and their Elimination", "Locomotion and Movement", "Neural Control and Coordination", "Chemical Coordination and Integration"),
        "Reproduction" to listOf("Reproduction", "Sexual Reproduction in Flowering Plants", "Human Reproduction", "Reproductive Health"),
        "Genetics & Evolution" to listOf("Genetics & Evolution", "Genetics", "Evolution", "Principles of Inheritance and Variation", "Molecular Basis of Inheritance"),
        "Biology & Human Welfare" to listOf("Biology & Human Welfare", "Microbes In Human Welfare", "Human Health and Disease", "Microbes in Human Welfare"),
        "Biotechnology" to listOf("Biotechnology", "Biotechnology - Principles and Processes", "Biotechnology and its Applications"),
        "Ecology & Environment" to listOf("Ecology & Environment", "Ecology", "Organisms and Populations", "Ecosystem", "Biodiversity and Conservation")
    )

    /** Set of chapters explicitly deleted from the official NTA 2026 syllabus. */
    val DELETED_CHAPTERS: Set<String> = setOf(
        // Chemistry
        "states of matter", "solid state", "surface chemistry", "hydrogen", "s block elements",
        "general principles and processes of isolation of elements", "metallurgy",
        "environmental chemistry", "polymers", "chemistry in everyday life",
        // Maths
        "mathematical reasoning", "principle of mathematical induction", "mathematical induction",
        // Physics
        "communication systems",
        // Biology
        "reproduction in organisms", "strategies for enhancement in food production",
        "environmental issues", "digestion and absorption", "transport in plants", "mineral nutrition"
    )

    /** Returns official syllabus unit names for the requested subject and exam. */
    fun getUnits(subject: String, exam: String = "JEE"): List<String> = when (subject) {
        "Physics"   -> PHYSICS_UNITS
        "Chemistry" -> CHEMISTRY_UNITS
        "Maths"     -> JEE_MATHS_UNITS
        "Biology"   -> NEET_BIOLOGY_UNITS
        else        -> emptyList()
    }

    /** Returns all valid aliases (including the unit itself) for database querying. */
    fun getAliasesForUnit(subject: String, unitName: String): List<String> {
        val aliases = UNIT_ALIASES[unitName]
        if (!aliases.isNullOrEmpty()) return aliases

        // Search case-insensitively
        val match = UNIT_ALIASES.entries.firstOrNull { it.key.equals(unitName, ignoreCase = true) }
        return match?.value ?: listOf(unitName)
    }

    /** Checks whether a chapter name belongs to the NTA deleted list. */
    fun isDeletedChapter(chapter: String): Boolean {
        val norm = chapter.lowercase().replace("&", "and").replace("-", " ").replace(Regex("[^a-z0-9 ]"), "").trim()
        return DELETED_CHAPTERS.contains(norm)
    }
}
