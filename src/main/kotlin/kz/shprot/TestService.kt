package kz.shprot

/**
 * Test service with intentional issues for code review demo
 */
class TestService {
    
    // Проблема: публичное mutable поле
    var config: MutableMap<String, Any> = mutableMapOf()
    
    // Проблема: SQL injection vulnerability
    fun getUserById(userId: String): String {
        val query = "SELECT * FROM users WHERE id = '$userId'"
        return query
    }
    
    // Проблема: Потенциальный NPE
    fun processData(data: Map<String, String>?): String {
        return data!!["key"]!!.uppercase()
    }
    
    // Проблема: Неэффективный алгоритм O(n²)
    fun findDuplicates(list: List<Int>): List<Int> {
        val duplicates = mutableListOf<Int>()
        for (i in list.indices) {
            for (j in list.indices) {
                if (i != j && list[i] == list[j] && !duplicates.contains(list[i])) {
                    duplicates.add(list[i])
                }
            }
        }
        return duplicates
    }
    
    // Проблема: Хардкод credentials
    fun connectToDatabase(): Boolean {
        val password = "admin123"
        val connectionString = "jdbc:mysql://localhost:3306/db?user=root&password=$password"
        println("Connecting to $connectionString")
        return true
    }
    
    // Хороший код для контраста
    fun calculateSum(numbers: List<Int>): Int {
        return numbers.fold(0) { acc, num -> acc + num }
    }
}
