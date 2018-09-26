package net.postchain.rell

import org.junit.Test

class SelectTest {
    @Test
    fun test() {

    }

    // user @ { name }
    // user @ { name = "Bob" }
    // user @ {}
    // user @ { name }
    // user @ { firstName = name or lastName = name }
    // user @ { company }
    // user @ { company.name = companyName }

    // query companyByUser(user): company = user.company
    // query companyByUser(user): company = company @ { user }
    // query companyByUser(user): company = company @ { user = user }

    // query usersByAge(yearsOld: integer): list<user> = user @ { currentYear() - yearOfBirth >= yearsOld }
    // query usersByCountry(country): list<user> = user @ { country }

    // query userFirstName(lastName: string): string = user @ { lastName } . firstName
    // query userPhoneAndEmail(lastName: string): (string, string) = user @ { lastName } . ( phone, eMail )
    // query userAge(lastName: string): integer = user @ { lastName } . ( currentYear() - yearOfBirth )
    // query userFirstName(user): string = user . firstName
    // query userPhoneAndEmail(user): (string, string) = user . ( phone, eMail )
    // query userAge(user): integer = user . ( currentYear() - yearOfBirth )

    // query userAndCompany(lastName: string): (user, company) = (user, company) @ { user.lastName = lastName, user.company_id = company.company_id }
    // query userAndCompanyNames(user_code: integer): (string, string, string) = (u: user, c:company) @ { u.code = user_code, u.company_id = c.company_id } . ( u.firstName, u.lastName, c.name )
}
